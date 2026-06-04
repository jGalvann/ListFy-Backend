package com.plugins

import com.database.*
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.cdimascio.dotenv.dotenv
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

object DatabaseFactory {

    fun init() {

        val dotenv = dotenv {
            ignoreIfMissing = true
        }

        fun env(key: String): String =
            dotenv[key] ?: System.getenv(key)
            ?: error("Variável de ambiente '$key' não encontrada.")

        val config = HikariConfig().apply {
            jdbcUrl              = env("DATABASE_URL")
            username             = env("DATABASE_USER")
            password             = env("DATABASE_PASSWORD")
            driverClassName      = "org.postgresql.Driver"
            maximumPoolSize      = 5
            isAutoCommit         = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
        }

        val dataSource = HikariDataSource(config)
        Database.connect(dataSource)

        // Cria as tabelas se não existirem
        transaction {
            SchemaUtils.create(
                TokenTable,
                LocalCompraTable,
                ListaCompraTable,
                ItemCompraTable,
                Historico,
                HistoricoItem
            )
        }
    }
}