package com.service

import com.database.ItemCompraTable
import com.database.ListaCompraTable
import com.database.TokenTable
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.update

@Serializable
data class CreateItemRequest(
    val nome: String,
    val quantidade: Int,
    val descricao: String? = null,
    val idLocal: Int? = null
)

@Serializable
data class ItemResponse(
    val idCompra: Int,
    val nome: String,
    val quantidade: Int,
    val descricao: String? = null,
    val idLocal: Int? = null,
    val comprado: Boolean
)

@Serializable
data class UpdateItemRequest(
    val nome: String? = null,
    val quantidade: Int? = null,
    val descricao: String? = null,
    val idLocal: Int? = null
)

@Serializable
data class UpdateStatusRequest(val status: String)


fun Route.itemRoutes() {
    post("/itens") {
        // 1. Validação de autenticação via middleware existente
        if (!requireValidToken()) return@post

        // Recupera o valor puro do token para identificar de quem é a lista
        val rawToken = call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
            ?: return@post

        val body = call.receive<CreateItemRequest>()

        // 2. Validações de Regra de Negócio (RN01)
        if (body.nome.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "O nome do item é obrigatório."))
            return@post
        }

        if (body.quantidade <= 0) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "A quantidade deve ser maior que zero."))
            return@post
        }

        // 3. Operações de Banco de Dados dentro da Transação
        val resultado = transaction {
            // Busca o id_token correspondente
            val tokenRow = TokenTable
                .selectAll()
                .where { TokenTable.valor eq rawToken }
                .single()
            val idTokenUsuario = tokenRow[TokenTable.idToken]

            // Busca ou cria uma Lista de Compras (ListaCompraTable) para este token caso não exista
            val listaRow = ListaCompraTable
                .selectAll()
                .where { ListaCompraTable.idToken eq idTokenUsuario }
                .singleOrNull()

            val idListaUsuario = if (listaRow == null) {
                ListaCompraTable.insert {
                    it[idToken] = idTokenUsuario
                }[ListaCompraTable.idLista]
            } else {
                listaRow[ListaCompraTable.idLista]
            }

            // Checa duplicidade: mesmo nome + mesmo idLocal na mesma lista (RN06)
            val existeDuplicado = ItemCompraTable
                .selectAll()
                .where {
                    (ItemCompraTable.idLista eq idListaUsuario) and
                            (ItemCompraTable.nome eq body.nome) and
                            (ItemCompraTable.idLocal eq body.idLocal)
                }
                .any()

            if (existeDuplicado) {
                return@transaction HttpStatusCode.Conflict
            }

            // Persiste o item com status=false (equivalente a pendente)
            ItemCompraTable.insert {
                it[idLista] = idListaUsuario
                it[idLocal] = body.idLocal
                it[nome] = body.nome
                it[quantidade] = body.quantidade
                it[descricao] = body.descricao
                it[status] = false
            }

            HttpStatusCode.Created
        }

        // 4. Retorno dos status HTTP correspondentes
        when (resultado) {
            HttpStatusCode.Conflict -> call.respond(
                HttpStatusCode.Conflict,
                mapOf("error" to "Item duplicado! Já existe um item com este nome e local na sua lista.")
            )
            HttpStatusCode.Created -> call.respond(
                HttpStatusCode.Created,
                mapOf("message" to "Item adicionado com sucesso!")
            )
            else -> call.respond(HttpStatusCode.InternalServerError)
        }
    }

    // GET /itens?status=pendente|comprado&idLocal=X
    get("/itens") {
        if (!requireValidToken()) return@get

        val rawToken = call.request.headers["Authorization"]
            ?.removePrefix("Bearer ")?.trim() ?: return@get

        // Query params
        val statusParam = call.request.queryParameters["status"]   // "pendente" | "comprado" | null
        val idLocalParam = call.request.queryParameters["idLocal"]?.toIntOrNull()

        val itens = transaction {
            // Resolve idLista do usuário
            val idTokenUsuario = TokenTable
                .selectAll()
                .where { TokenTable.valor eq rawToken }
                .single()[TokenTable.idToken]

            val listaRow = ListaCompraTable
                .selectAll()
                .where { ListaCompraTable.idToken eq idTokenUsuario }
                .singleOrNull() ?: return@transaction emptyList()

            val idListaUsuario = listaRow[ListaCompraTable.idLista]

            // Monta a query base
            var query = ItemCompraTable
                .selectAll()
                .where { ItemCompraTable.idLista eq idListaUsuario }

            // Filtro por status (RN04)
            when (statusParam) {
                "pendente"  -> query = query.andWhere { ItemCompraTable.status eq false }
                "comprado"  -> query = query.andWhere { ItemCompraTable.status eq true }
                null        -> { /* sem filtro: retorna tudo */ }
                else -> return@transaction null   // valor inválido → sinaliza 400
            }

            // Filtro por local
            if (idLocalParam != null) {
                query = query.andWhere { ItemCompraTable.idLocal eq idLocalParam }
            }

            query.map { row ->
                ItemResponse(
                    idCompra   = row[ItemCompraTable.idCompra],
                    nome       = row[ItemCompraTable.nome],
                    quantidade = row[ItemCompraTable.quantidade],
                    descricao  = row[ItemCompraTable.descricao],
                    idLocal    = row[ItemCompraTable.idLocal],
                    comprado   = row[ItemCompraTable.status]
                )
            }
        }

        when (itens) {
            null -> call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Valor inválido para 'status'. Use 'pendente' ou 'comprado'.")
            )
            else -> call.respond(HttpStatusCode.OK, itens)
        }
    }

    put("/itens/{id}") {
        if (!requireValidToken()) return@put

        val rawToken = call.request.headers["Authorization"]
            ?.removePrefix("Bearer ")?.trim() ?: return@put

        val idCompra = call.parameters["id"]?.toIntOrNull()
        if (idCompra == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "ID inválido."))
            return@put
        }

        val body = call.receive<UpdateItemRequest>()

        // RN07: pelo menos um campo deve ser enviado
        if (body.nome == null && body.quantidade == null && body.descricao == null && body.idLocal == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Informe ao menos um campo para atualizar."))
            return@put
        }

        // RN01: validações se os campos foram enviados
        if (body.nome != null && body.nome.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "O nome do item não pode ser vazio."))
            return@put
        }
        if (body.quantidade != null && body.quantidade <= 0) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "A quantidade deve ser maior que zero."))
            return@put
        }

        val resultado = transaction {
            // Garante que o item pertence à lista do token
            val idTokenUsuario = TokenTable
                .selectAll()
                .where { TokenTable.valor eq rawToken }
                .single()[TokenTable.idToken]

            val idListaUsuario = ListaCompraTable
                .selectAll()
                .where { ListaCompraTable.idToken eq idTokenUsuario }
                .singleOrNull()?.get(ListaCompraTable.idLista)
                ?: return@transaction HttpStatusCode.NotFound

            val itemRow = ItemCompraTable
                .selectAll()
                .where {
                    (ItemCompraTable.idCompra eq idCompra) and
                            (ItemCompraTable.idLista eq idListaUsuario)
                }
                .singleOrNull() ?: return@transaction HttpStatusCode.NotFound

            // RN07: bloqueia edição se já comprado
            if (itemRow[ItemCompraTable.status]) {
                return@transaction HttpStatusCode.Conflict
            }

            ItemCompraTable.update(
                where = { ItemCompraTable.idCompra eq idCompra }
            ) {
                if (body.nome != null)       it[nome]       = body.nome
                if (body.quantidade != null) it[quantidade] = body.quantidade
                if (body.descricao != null)  it[descricao]  = body.descricao
                if (body.idLocal != null)    it[idLocal]    = body.idLocal
            }

            HttpStatusCode.OK
        }

        when (resultado) {
            HttpStatusCode.NotFound -> call.respond(HttpStatusCode.NotFound, mapOf("error" to "Item não encontrado."))
            HttpStatusCode.Conflict -> call.respond(HttpStatusCode.Conflict, mapOf("error" to "Não é possível editar um item já comprado."))
            HttpStatusCode.OK       -> call.respond(HttpStatusCode.OK, mapOf("message" to "Item atualizado com sucesso!"))
            else                    -> call.respond(HttpStatusCode.InternalServerError)
        }
    }

    delete("/itens/{id}") {
        if (!requireValidToken()) return@delete

        val rawToken = call.request.headers["Authorization"]
            ?.removePrefix("Bearer ")?.trim() ?: return@delete

        val idCompra = call.parameters["id"]?.toIntOrNull()
        if (idCompra == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "ID inválido."))
            return@delete
        }

        val resultado = transaction {
            val idTokenUsuario = TokenTable
                .selectAll()
                .where { TokenTable.valor eq rawToken }
                .single()[TokenTable.idToken]

            val idListaUsuario = ListaCompraTable
                .selectAll()
                .where { ListaCompraTable.idToken eq idTokenUsuario }
                .singleOrNull()?.get(ListaCompraTable.idLista)
                ?: return@transaction HttpStatusCode.NotFound

            val itemRow = ItemCompraTable
                .selectAll()
                .where {
                    (ItemCompraTable.idCompra eq idCompra) and
                            (ItemCompraTable.idLista eq idListaUsuario)
                }
                .singleOrNull() ?: return@transaction HttpStatusCode.NotFound

            if (itemRow[ItemCompraTable.status]) {
                return@transaction HttpStatusCode.Conflict
            }

            ItemCompraTable.deleteWhere { ItemCompraTable.idCompra eq idCompra }

            HttpStatusCode.NoContent
        }

        when (resultado) {
            HttpStatusCode.NotFound -> call.respond(HttpStatusCode.NotFound, mapOf("error" to "Item não encontrado."))
            HttpStatusCode.Conflict -> call.respond(HttpStatusCode.Conflict, mapOf("error" to "Não é possível remover um item já comprado."))
            HttpStatusCode.NoContent -> call.respond(HttpStatusCode.NoContent)
            else -> call.respond(HttpStatusCode.InternalServerError)
        }
    }

    patch("/itens/{id}/status") {
        if (!requireValidToken()) return@patch

        val rawToken = call.request.headers["Authorization"]
            ?.removePrefix("Bearer ")?.trim() ?: return@patch

        val idCompra = call.parameters["id"]?.toIntOrNull()
        if (idCompra == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "ID inválido."))
            return@patch
        }

        val body = call.receive<UpdateStatusRequest>()

        // RN05: só aceita os dois valores válidos
        val novoStatus = when (body.status) {
            "comprado" -> true
            "pendente" -> false
            else -> {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Status inválido. Use 'comprado' ou 'pendente'."))
                return@patch
            }
        }

        val resultado = transaction {
            val idTokenUsuario = TokenTable
                .selectAll()
                .where { TokenTable.valor eq rawToken }
                .single()[TokenTable.idToken]

            val idListaUsuario = ListaCompraTable
                .selectAll()
                .where { ListaCompraTable.idToken eq idTokenUsuario }
                .singleOrNull()?.get(ListaCompraTable.idLista)
                ?: return@transaction null

            val itemRow = ItemCompraTable
                .selectAll()
                .where {
                    (ItemCompraTable.idCompra eq idCompra) and
                            (ItemCompraTable.idLista eq idListaUsuario)
                }
                .singleOrNull() ?: return@transaction null

            ItemCompraTable.update({ ItemCompraTable.idCompra eq idCompra }) {
                it[status] = novoStatus
            }

            // Retorna o item atualizado
            ItemResponse(
                idCompra   = itemRow[ItemCompraTable.idCompra],
                nome       = itemRow[ItemCompraTable.nome],
                quantidade = itemRow[ItemCompraTable.quantidade],
                descricao  = itemRow[ItemCompraTable.descricao],
                idLocal    = itemRow[ItemCompraTable.idLocal],
                comprado   = novoStatus
            )
        }

        if (resultado == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Item não encontrado."))
        } else {
            call.respond(HttpStatusCode.OK, resultado)
        }
    }
}