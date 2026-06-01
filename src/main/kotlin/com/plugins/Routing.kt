package com.plugins

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import com.service.authRoutes
import com.service.tokenRoutes       // <- import novo
import com.service.requireValidToken

fun Application.configureRouting() {
    routing {
        authRoutes()
        tokenRoutes()               // <- registra a rota

        get("/") {
            call.respondText("Hello, World!")
        }

        get("/protected") {
            if (!requireValidToken()) return@get

            call.respondText("Você está autenticado!")
        }

        get("/admin") {
            if (!requireValidToken(requiredRole = "admin")) return@get

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