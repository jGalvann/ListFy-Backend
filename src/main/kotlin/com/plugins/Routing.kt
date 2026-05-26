package com.plugins

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import com.service.authRoutes
import com.service.requireValidToken

fun Application.configureRouting() {
    routing {
        authRoutes()

        get("/") {
            call.respondText("Hello, World!")
        }

        get("/protected") {
            if (!requireValidToken()) return@get // Interrompe a execução aqui caso falte autenticação

            call.respondText("Você está autenticado!")
        }

// Exemplo com role específica corrigida:
        get("/admin") {
            if (!requireValidToken(requiredRole = "ADMIN")) return@get

            call.respondText("Área admin!")
        }

        webSocket("/ws") {
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    outgoing.send(Frame.Text("YOU SAID: $text"))
                    if (text.equals("bye", ignoreCase = true)) {
                        close(CloseReason(CloseReason.Codes.NORMAL, "Client said BYE"))
                    }
                }
            }
        }
    }
}