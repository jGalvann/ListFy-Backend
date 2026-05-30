package com.service

import com.database.TokenTable
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.util.AttributeKey
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate
import io.ktor.server.routing.RoutingContext
import org.jetbrains.exposed.sql.selectAll

// "Etiqueta" usada para guardar e recuperar o role do ApplicationCall
val RoleAttributeKey = AttributeKey<String>("role")

/**
 * Interceptor reutilizável. Espera o header:
 * Authorization: Bearer <token>
 * Retorna 401 se inválido/expirado/inativo.
 * Injeta o role no ApplicationCall.attributes após validação bem-sucedida.
 * Retorna [Boolean] indicando se a requisição pode prosseguir.
 */
suspend fun RoutingContext.requireValidToken(requiredRole: String? = null): Boolean {
    val raw = call.request.headers["Authorization"]
    val tokenValor = raw?.removePrefix("Bearer ")?.trim()

    if (tokenValor.isNullOrBlank()) {
        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Token ausente"))
        return false
    }

    val today = LocalDate.now()
    val tokenRow = transaction {
        TokenTable
            .selectAll()
            .where {
                (TokenTable.valor eq tokenValor) and
                        (TokenTable.ativoToken eq true)
            }
            .singleOrNull()
    }

    if (tokenRow == null || tokenRow[TokenTable.dataExpiracao].isBefore(today)) {
        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Token inválido ou expirado"))
        return false
    }

    if (requiredRole != null && tokenRow[TokenTable.role] != requiredRole) {
        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Sem permissão"))
        return false
    }

    // Injeta o role na "mochila" do call para as rotas usarem sem ir ao banco de novo
    call.attributes.put(RoleAttributeKey, tokenRow[TokenTable.role])

    return true
}