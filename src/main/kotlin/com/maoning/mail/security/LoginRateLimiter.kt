package com.maoning.mail.security

import com.maoning.mail.config.AppConfig
import com.maoning.mail.jpa.LoginAttemptEntity
import com.maoning.mail.jpa.LoginAttemptRepository
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

open class LoginRateLimiter(
    private val config: AppConfig,
    private val repository: LoginAttemptRepository? = null,
    private val dataSource: DataSource? = null
) {
    constructor(config: AppConfig, dataSource: DataSource) : this(config, null, dataSource)

    private fun conn(): Connection = requireNotNull(dataSource).connection

    open fun assertAllowed(username: String, ip: String) {
        val since = Instant.now().minusSeconds(config.loginWindowSeconds).toEpochMilli()
        val failures = when {
            repository != null -> repository.countByUsernameAndIpAndSuccessFalseAndCreatedAtGreaterThanEqual(username.lowercase(), ip, since)
            dataSource != null -> conn().use { c ->
                c.prepareStatement("select count(*) from login_attempts where username=? and ip=? and success=false and created_at>=?").use { ps ->
                    ps.setString(1, username.lowercase())
                    ps.setString(2, ip)
                    ps.setLong(3, since)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0 }
                }
            }
            else -> 0
        }
        require(failures < config.loginMaxFailures) { "Too many failed login attempts, try later" }
    }

    open fun record(username: String, ip: String, success: Boolean) {
        val now = Instant.now().toEpochMilli()
        if (repository != null) {
            repository.save(LoginAttemptEntity(UUID.randomUUID().toString(), username.lowercase(), ip, success, now))
            return
        }
        if (dataSource != null) {
            conn().use { c ->
                c.prepareStatement("insert into login_attempts(id, username, ip, success, created_at) values(?,?,?,?,?)").use { ps ->
                    ps.setString(1, UUID.randomUUID().toString())
                    ps.setString(2, username.lowercase())
                    ps.setString(3, ip)
                    ps.setBoolean(4, success)
                    ps.setLong(5, now)
                    ps.executeUpdate()
                }
            }
        }
    }
}
