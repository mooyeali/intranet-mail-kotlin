package com.maoning.mail.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource

object DataSourceFactory {
    fun hikari(url: String, user: String, password: String): HikariDataSource {
        Class.forName("org.h2.Driver")
        val config = HikariConfig().apply {
            jdbcUrl = url
            username = user
            this.password = password
            maximumPoolSize = 10
            minimumIdle = 1
            poolName = "intranet-mail-hikari"
            isAutoCommit = true
        }
        return HikariDataSource(config)
    }
}

fun DataSource.closeIfPossible() {
    if (this is AutoCloseable) close()
}
