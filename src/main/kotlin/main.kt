package com

import com.plugins.DatabaseFactory
import com.plugins.configureSerialization
import com.plugins.configureRouting
import com.plugins.configureSecurity
import com.plugins.configureWebsockets
import io.ktor.server.application.*

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    DatabaseFactory.init()
    configureSerialization()
    configureSecurity()
    configureWebsockets()
    configureRouting()
}