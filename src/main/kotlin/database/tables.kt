package com.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date

// Tabela Token
// Tabela Token Corrigida
object TokenTable : Table("token") {
    val idToken        = integer("id_token").autoIncrement()
    val valor          = varchar("valor", 64).uniqueIndex()
    val dataExpiracao  = date("data_expiracao")
    val ativoToken     = bool("ativo_token").default(true)
    val role           = varchar("role", 20)

    // Deixamos APENAS o idToken como Chave Primária
    override val primaryKey = PrimaryKey(idToken)
}
// Tabela LocalCompra
object LocalCompraTable : Table("local_compra") {
    val idLocal    = integer("id_local").autoIncrement()
    val nome       = varchar("nome", 50).nullable()
    val ativoLocal = bool("ativo_local").default(true)

    override val primaryKey = PrimaryKey(idLocal)
}

// Tabela ListaCompra
object ListaCompraTable : Table("lista_compra") {
    val idLista = integer("id_lista").autoIncrement()
    val idToken = integer("id_token").references(TokenTable.idToken)

    override val primaryKey = PrimaryKey(idLista)
}

// Tabela ItemCompra
object ItemCompraTable : Table("item_compra") {
    val idCompra   = integer("id_compra").autoIncrement()
    val idLista    = integer("id_lista").references(ListaCompraTable.idLista)
    val idLocal    = integer("id_local").references(LocalCompraTable.idLocal).nullable()
    val nome       = varchar("nome", 50)
    val quantidade = integer("quantidade")
    val descricao  = varchar("descricao", 250).nullable()
    val status     = bool("status").default(false)

    override val primaryKey = PrimaryKey(idCompra)
}

// Tabela Historico
object Historico : Table("historico") {
    val idHistorico    = integer("id_historico").autoIncrement()
    val idLista        = integer("id_lista").references(ListaCompraTable.idLista)
    val dataFinalizacao = date("data_finalizacao")

    override val primaryKey = PrimaryKey(idHistorico)
}

// Tabela HistoricoItem
object HistoricoItem : Table("historico_item") {
    val idHistorico = integer("id_historico").references(Historico.idHistorico)
    val idCompra    = integer("id_compra").references(ItemCompraTable.idCompra)

    override val primaryKey = PrimaryKey(idHistorico, idCompra)
}