package com.service

import com.database.TokenTable
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate

@Serializable
data class ValidateTokenRequest(val token: String)

@Serializable
data class ValidateTokenResponse(val valido: Boolean, val role: String? = null)

fun Route.authRoutes() {
    post("/auth/validate") {
        val body = call.receive<ValidateTokenRequest>()
        val today = LocalDate.now()

        val tokenRow = transaction {
            TokenTable
                .selectAll()
                .where {
                    (TokenTable.valor eq body.token) and
                            (TokenTable.ativoToken eq true)
                }
                .singleOrNull()
        }

        if (tokenRow == null) {
            call.respond(HttpStatusCode.Unauthorized, ValidateTokenResponse(valido = false))
            return@post
        }

        val expiracao = tokenRow[TokenTable.dataExpiracao]
        if (expiracao.isBefore(today)) {
            call.respond(HttpStatusCode.Unauthorized, ValidateTokenResponse(valido = false))
            return@post
        }

        call.respond(ValidateTokenResponse(valido = true, role = tokenRow[TokenTable.role]))
    }
}