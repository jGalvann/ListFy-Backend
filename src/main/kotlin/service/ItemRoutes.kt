package com.service

import com.database.ItemCompraTable
import com.database.ListaCompraTable
import com.database.TokenTable
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
data class CreateItemRequest(
    val nome: String,
    val quantidade: Int,
    val descricao: String? = null,
    val idLocal: Int? = null
)

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
}