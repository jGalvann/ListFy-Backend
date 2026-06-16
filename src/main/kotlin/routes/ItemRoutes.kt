package com.routes

import com.database.ItemCompraTable
import com.database.ListaCompraTable
import com.database.TokenTable
import com.service.requireValidToken
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
import java.time.LocalDate

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
    val comprado: Boolean,
    val dataCompra: String? = null
)

@Serializable
data class UpdateItemRequest(
    val nome: String? = null,
    val quantidade: Int? = null,
    val descricao: String? = null,
    val idLocal: Int? = null
)

@Serializable
data class ItemStatusUpdate(val id: Int, val status: String)

@Serializable
data class UpdateMultipleStatusRequest(val itens: List<ItemStatusUpdate>)

/**
 * Resolve o idLista compartilhado para um dado rawToken.
 *
 * Regra: se o token for do tipo USER e tiver um idTokenAdmin preenchido,
 * usa a lista do admin dono. Assim admin e usuários vinculados compartilham
 * a mesma lista. Se for o próprio admin (idTokenAdmin == null), usa a lista
 * dele mesmo. Cria a lista na primeira vez, caso ainda não exista.
 *
 * ATENÇÃO: deve ser chamado DENTRO de um bloco transaction {}.
 */
fun resolverIdLista(rawToken: String): Int? {
    val tokenRow = TokenTable
        .selectAll()
        .where { TokenTable.valor eq rawToken }
        .singleOrNull() ?: return null

    // Se for usuário com admin vinculado, aponta para o token do admin.
    // Se for o próprio admin (ou token sem vínculo), usa o seu próprio idToken.
    val idTokenDono = tokenRow[TokenTable.idTokenAdmin]
        ?: tokenRow[TokenTable.idToken]

    val listaRow = ListaCompraTable
        .selectAll()
        .where { ListaCompraTable.idToken eq idTokenDono }
        .singleOrNull()

    return if (listaRow == null) {
        // Primeira vez: cria a lista vinculada ao token dono (normalmente o admin)
        ListaCompraTable.insert {
            it[idToken] = idTokenDono
        }[ListaCompraTable.idLista]
    } else {
        listaRow[ListaCompraTable.idLista]
    }
}

fun Route.itemRoutes() {

    // ─── POST /itens ────────────────────────────────────────────────────────────
    post("/itens") {
        if (!requireValidToken()) return@post

        val rawToken = call.request.headers["Authorization"]
            ?.removePrefix("Bearer ")?.trim() ?: return@post

        val body = call.receive<CreateItemRequest>()

        if (body.nome.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "O nome do item é obrigatório."))
            return@post
        }
        if (body.quantidade <= 0) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "A quantidade deve ser maior que zero."))
            return@post
        }

        val resultado = transaction {
            val idListaCompartilhada = resolverIdLista(rawToken)
                ?: return@transaction HttpStatusCode.Unauthorized

            val existeDuplicado = ItemCompraTable
                .selectAll()
                .where {
                    (ItemCompraTable.idLista eq idListaCompartilhada) and
                            (ItemCompraTable.nome eq body.nome) and
                            (ItemCompraTable.idLocal eq body.idLocal) and
                            (ItemCompraTable.status eq false)
                }
                .any()

            if (existeDuplicado) return@transaction HttpStatusCode.Conflict

            ItemCompraTable.insert {
                it[idLista]    = idListaCompartilhada
                it[idLocal]    = body.idLocal
                it[nome]       = body.nome
                it[quantidade] = body.quantidade
                it[descricao]  = body.descricao
                it[status]     = false
            }

            HttpStatusCode.Created
        }

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

    // ─── GET /itens?status=pendente|comprado&idLocal=X ──────────────────────────
    get("/itens") {
        if (!requireValidToken()) return@get

        val rawToken = call.request.headers["Authorization"]
            ?.removePrefix("Bearer ")?.trim() ?: return@get

        val statusParam  = call.request.queryParameters["status"]
        val idLocalParam = call.request.queryParameters["idLocal"]?.toIntOrNull()

        val itens = transaction {
            val idListaCompartilhada = resolverIdLista(rawToken)
                ?: return@transaction emptyList()

            var query = ItemCompraTable
                .selectAll()
                .where { ItemCompraTable.idLista eq idListaCompartilhada }

            when (statusParam) {
                "pendente" -> query = query.andWhere { ItemCompraTable.status eq false }
                "comprado" -> query = query.andWhere { ItemCompraTable.status eq true }
                null       -> { /* sem filtro */ }
                else       -> return@transaction null   // valor inválido → 400
            }

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
                    comprado   = row[ItemCompraTable.status],
                    dataCompra = row[ItemCompraTable.dataCompra]?.toString()
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

    // ─── PUT /itens/{id} ────────────────────────────────────────────────────────
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

        if (body.nome == null && body.quantidade == null && body.descricao == null && body.idLocal == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Informe ao menos um campo para atualizar."))
            return@put
        }
        if (body.nome != null && body.nome.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "O nome do item não pode ser vazio."))
            return@put
        }
        if (body.quantidade != null && body.quantidade <= 0) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "A quantidade deve ser maior que zero."))
            return@put
        }

        val resultado = transaction {
            val idListaCompartilhada = resolverIdLista(rawToken)
                ?: return@transaction HttpStatusCode.NotFound

            val itemRow = ItemCompraTable
                .selectAll()
                .where {
                    (ItemCompraTable.idCompra eq idCompra) and
                            (ItemCompraTable.idLista  eq idListaCompartilhada)
                }
                .singleOrNull() ?: return@transaction HttpStatusCode.NotFound

            if (itemRow[ItemCompraTable.status]) return@transaction HttpStatusCode.Conflict

            ItemCompraTable.update({ ItemCompraTable.idCompra eq idCompra }) {
                if (body.nome       != null) it[nome]       = body.nome
                if (body.quantidade != null) it[quantidade] = body.quantidade
                if (body.descricao  != null) it[descricao]  = body.descricao
                if (body.idLocal    != null) it[idLocal]    = body.idLocal
            }

            HttpStatusCode.OK
        }

        when (resultado) {
            HttpStatusCode.NotFound  -> call.respond(HttpStatusCode.NotFound,  mapOf("error" to "Item não encontrado."))
            HttpStatusCode.Conflict  -> call.respond(HttpStatusCode.Conflict,  mapOf("error" to "Não é possível editar um item já comprado."))
            HttpStatusCode.OK        -> call.respond(HttpStatusCode.OK,        mapOf("message" to "Item atualizado com sucesso!"))
            else                     -> call.respond(HttpStatusCode.InternalServerError)
        }
    }

    // ─── DELETE /itens/{id} ─────────────────────────────────────────────────────
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
            val idListaCompartilhada = resolverIdLista(rawToken)
                ?: return@transaction HttpStatusCode.NotFound

            val itemRow = ItemCompraTable
                .selectAll()
                .where {
                    (ItemCompraTable.idCompra eq idCompra) and
                            (ItemCompraTable.idLista  eq idListaCompartilhada)
                }
                .singleOrNull() ?: return@transaction HttpStatusCode.NotFound

            if (itemRow[ItemCompraTable.status]) return@transaction HttpStatusCode.Conflict

            ItemCompraTable.deleteWhere { ItemCompraTable.idCompra eq idCompra }

            HttpStatusCode.NoContent
        }

        when (resultado) {
            HttpStatusCode.NotFound  -> call.respond(HttpStatusCode.NotFound,  mapOf("error" to "Item não encontrado."))
            HttpStatusCode.Conflict  -> call.respond(HttpStatusCode.Conflict,  mapOf("error" to "Não é possível remover um item já comprado."))
            HttpStatusCode.NoContent -> call.respond(HttpStatusCode.NoContent)
            else                     -> call.respond(HttpStatusCode.InternalServerError)
        }
    }

    // ─── PATCH /itens/status ────────────────────────────────────────────────────
    patch("/itens/status") {
        if (!requireValidToken()) return@patch

        val rawToken = call.request.headers["Authorization"]
            ?.removePrefix("Bearer ")?.trim() ?: return@patch

        val body = call.receive<UpdateMultipleStatusRequest>()

        if (body.itens.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Informe ao menos um item."))
            return@patch
        }

        val statusMap = body.itens.associate { item ->
            val novoStatus = when (item.status) {
                "comprado" -> true
                "pendente" -> false
                else -> {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Status inválido no item ${item.id}. Use 'comprado' ou 'pendente'.")
                    )
                    return@patch
                }
            }
            item.id to novoStatus
        }

        transaction {
            val idListaCompartilhada = resolverIdLista(rawToken)
                ?: return@transaction

            statusMap.forEach { (idCompra, novoStatus) ->
                ItemCompraTable.update({
                    (ItemCompraTable.idCompra eq idCompra) and
                            (ItemCompraTable.idLista  eq idListaCompartilhada)
                }) {
                    it[status]     = novoStatus
                    it[dataCompra] = if (novoStatus) LocalDate.now() else null
                }
            }
        }

        call.respond(HttpStatusCode.OK, mapOf("message" to "Itens atualizados com sucesso!"))
    }
}