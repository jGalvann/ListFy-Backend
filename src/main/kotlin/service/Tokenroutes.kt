package com.service

import com.database.TokenTable
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate
import java.util.UUID

@Serializable
data class TokenGeradoResponse(val token: String, val dataExpiracao: String)

fun Route.tokenRoutes() {
    post("/tokens") {
        // Só admin pode chamar essa rota
        if (!requireValidToken(requiredRole = "admin")) return@post

        // Pega o idToken do admin que está fazendo a requisição
        val rawToken = call.request.headers["Authorization"]
            ?.removePrefix("Bearer ")?.trim() ?: return@post

        // Gera um UUID aleatório como valor do token
        val novoToken = UUID.randomUUID().toString()

        // Data de expiração: 1 ano a partir de hoje
        val dataExpiracao = LocalDate.now().plusYears(1)

        // Persiste na tabela com role=usuario
        transaction {
            // Resolve o idToken do admin logado

            val adminRow = TokenTable
                .selectAll()
                .where { TokenTable.valor eq rawToken }
                .single()
            val idAdmin = adminRow[TokenTable.idToken]

            TokenTable.insert {
                it[TokenTable.valor]         = novoToken
                it[TokenTable.dataExpiracao] = dataExpiracao
                it[TokenTable.ativoToken]    = true
                it[TokenTable.role]          = "USER"
                it[TokenTable.idTokenAdmin] = idAdmin
            }
        }

        call.respond(
            HttpStatusCode.Created,
            TokenGeradoResponse(
                token = novoToken,
                dataExpiracao = dataExpiracao.toString()
            )
        )
    }
}