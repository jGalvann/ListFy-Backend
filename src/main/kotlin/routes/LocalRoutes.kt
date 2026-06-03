package com.routes

import com.database.LocalCompraTable
import com.service.requireValidToken
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import com.database.ItemCompraTable
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
data class CreateLocalRequest(val nome: String)

@Serializable
data class LocalResponse(val idLocal: Int, val nome: String, val ativo: Boolean)

@Serializable
data class UpdateLocalStatusRequest(val ativo: Boolean)

fun Route.localRoutes() {
    post("/locais") {
        if (!requireValidToken()) return@post

        val body = call.receive<CreateLocalRequest>()

        if (body.nome.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "O nome do local é obrigatório."))
            return@post
        }

        if (body.nome.length > 50) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "O nome do local deve ter no máximo 50 caracteres."))
            return@post
        }

        val resultado = transaction {
            val existe = LocalCompraTable
                .selectAll()
                .where { LocalCompraTable.nome eq body.nome }
                .any()

            if (existe) return@transaction null

            val id = LocalCompraTable.insert {
                it[nome]       = body.nome
                it[ativoLocal] = true
            }[LocalCompraTable.idLocal]

            LocalResponse(idLocal = id, nome = body.nome, ativo = true)
        }

        if (resultado == null) {
            call.respond(HttpStatusCode.Conflict, mapOf("error" to "Já existe um local com este nome."))
        } else {
            call.respond(HttpStatusCode.Created, resultado)
        }
    }

    get("/locais") {
        if (!requireValidToken()) return@get

        val ativoParam = call.request.queryParameters["ativo"]

        val ativo = when (ativoParam) {
            "true"  -> true
            "false" -> false
            null    -> null
            else    -> {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Valor inválido para 'ativo'. Use 'true' ou 'false'."))
                return@get
            }
        }

        val locais = transaction {
            var query = LocalCompraTable.selectAll()

            if (ativo != null) {
                query = query.andWhere { LocalCompraTable.ativoLocal eq ativo }
            }

            query.map { row ->
                LocalResponse(
                    idLocal = row[LocalCompraTable.idLocal],
                    nome    = row[LocalCompraTable.nome] ?: "",
                    ativo   = row[LocalCompraTable.ativoLocal]
                )
            }
        }

        call.respond(HttpStatusCode.OK, locais)
    }

    patch("/locais/{id}/status") {
        if (!requireValidToken()) return@patch

        val idLocal = call.parameters["id"]?.toIntOrNull()
        if (idLocal == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "ID inválido."))
            return@patch
        }

        val body = call.receive<UpdateLocalStatusRequest>()

        val resultado = transaction {
            val localRow = LocalCompraTable
                .selectAll()
                .where { LocalCompraTable.idLocal eq idLocal }
                .singleOrNull() ?: return@transaction "not_found"

            if (localRow[LocalCompraTable.ativoLocal] == body.ativo) {
                return@transaction "already_same"
            }

            // RN08: só bloqueia ao inativar
            if (!body.ativo) {
                val temPendentes = ItemCompraTable
                    .selectAll()
                    .where {
                        (ItemCompraTable.idLocal eq idLocal) and
                                (ItemCompraTable.status eq false)
                    }
                    .any()

                if (temPendentes)
                    return@transaction "has_pending"
            }

            LocalCompraTable.update({ LocalCompraTable.idLocal eq idLocal }) {
                it[ativoLocal] = body.ativo
            }

            "ok"
        }

        when (resultado) {
            "not_found"    -> call.respond(HttpStatusCode.NotFound, mapOf("error" to "Local não encontrado."))
            "already_same" -> call.respond(HttpStatusCode.Conflict, mapOf("error" to "Local já está neste estado."))
            "ok"           -> call.respond(HttpStatusCode.OK, mapOf("message" to "Status do local atualizado com sucesso."))
            "has_pending" -> call.respond(HttpStatusCode.Conflict, mapOf("error" to "Existem itens pendentes vinculados a este local."))
            else -> call.respond(HttpStatusCode.InternalServerError)
        }
    }
}