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
        val dotenv = dotenv()

        val config = HikariConfig().apply {
            jdbcUrl              = dotenv["DATABASE_URL"]
            username             = dotenv["DATABASE_USER"]
            password             = dotenv["DATABASE_PASSWORD"]
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