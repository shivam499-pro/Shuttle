package com.dynashuttle

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import com.dynashuttle.config.Env
import com.dynashuttle.db.DatabaseFactory
import com.dynashuttle.plugins.*

fun main() {
    val port = Env.get("PORT", "8080")!!.toInt()

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        DatabaseFactory.init()
        configureSerialization()
        configureCORS()
        configureStatusPages()
        configureAuthentication()
        configureWebSockets()
        configureRouting()
    }.start(wait = true)
}
