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
        // Carrega o dotenv com segurança para não quebrar em produção
        val dotenv = try {
            dotenv { ignoreIfMissing = true }
        } catch (e: Exception) {
            null
        }

        // Função de ajuda corrigida: tenta dotenv, depois Railway, se não achar usa o padrão opcional
        fun env(key: String, defaultValue: String? = null): String {
            return dotenv?.get(key)
                ?: System.getenv(key)
                ?: defaultValue
                ?: error("Variável de ambiente '$key' não encontrada no .env ou no Railway.")
        }

        val config = HikariConfig().apply {
            driverClassName      = "org.postgresql.Driver"
            jdbcUrl              = env("DATABASE_URL")
            username             = env("DATABASE_USER", "postgres") // Se não achar DATABASE_USER, usa "postgres"
            password             = env("DATABASE_PASSWORD")

            // Configurações recomendadas para nuvem e plano gratuito
            maximumPoolSize      = 3
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
            )
        }
    }
}