package com.maoning.mail.security

import com.maoning.mail.config.AppConfig
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

class LoginRateLimiter(
    private val config: AppConfig,
    private val url: String,
    private val user: String,
    private val password: String
) {
    private fun conn(): Connection = DriverManager.getConnection(url, user, password)

    fun assertAllowed(username: String, ip: String) {
        val since = Instant.now().minusSeconds(config.loginWindowSeconds).toEpochMilli()
        val failures = conn().use { c ->
            c.prepareStatement("select count(*) from login_attempts where username=? and ip=? and success=false and created_at>=?").use { ps ->
                ps.setString(1, username.lowercase())
                ps.setString(2, ip)
                ps.setLong(3, since)
                ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
            }
        }
        require(failures < config.loginMaxFailures) { "Too many failed login attempts, try later" }
    }

    fun record(username: String, ip: String, success: Boolean) {
        conn().use { c ->
            c.prepareStatement("insert into login_attempts(id, username, ip, success, created_at) values(?,?,?,?,?)").use { ps ->
                ps.setString(1, UUID.randomUUID().toString())
                ps.setString(2, username.lowercase())
                ps.setString(3, ip)
                ps.setBoolean(4, success)
                ps.setLong(5, Instant.now().toEpochMilli())
                ps.executeUpdate()
            }
        }
    }
}
