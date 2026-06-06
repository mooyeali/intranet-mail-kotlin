package com.maoning.mail.security

import com.maoning.mail.config.AppConfig
import com.maoning.mail.db.DataSourceFactory
import org.flywaydb.core.Flyway
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class LoginRateLimiterEdgeTest {
    @Test
    fun locksOutAfterConfiguredFailuresForSameUserAndIp() {
        val fixture = fixture(AppConfig(domain = "test.local", loginMaxFailures = 2, loginWindowSeconds = 300))

        fixture.limiter.record("Alice", "10.0.0.5", success = false)
        fixture.limiter.record("alice", "10.0.0.5", success = false)

        assertFailsWith<IllegalArgumentException> {
            fixture.limiter.assertAllowed("ALICE", "10.0.0.5")
        }
    }

    @Test
    fun resetsLockoutAfterWindowExpires() {
        val fixture = fixture(AppConfig(domain = "test.local", loginMaxFailures = 1, loginWindowSeconds = 1))

        fixture.insertAttempt("alice", "10.0.0.5", false, System.currentTimeMillis() - 5_000L)

        fixture.limiter.assertAllowed("alice", "10.0.0.5")
    }

    @Test
    fun successfulLoginsDoNotCountTowardFailureLockout() {
        val fixture = fixture(AppConfig(domain = "test.local", loginMaxFailures = 1, loginWindowSeconds = 300))

        fixture.limiter.record("alice", "10.0.0.5", success = true)

        fixture.limiter.assertAllowed("alice", "10.0.0.5")
    }

    private data class Fixture(val limiter: LoginRateLimiter, val insertAttempt: (String, String, Boolean, Long) -> Unit)

    private fun fixture(config: AppConfig): Fixture {
        val workDir = Files.createTempDirectory("login-rate-limiter-test")
        val testConfig = config.copy(
            h2Url = "jdbc:h2:${workDir.resolve("mail-db")};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
            adminToken = "test-admin-token"
        )
        val dataSource = DataSourceFactory.hikari(testConfig.h2Url, testConfig.h2User, testConfig.h2Password)
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate()
        val limiter = LoginRateLimiter(testConfig, dataSource)
        return Fixture(limiter) { username, ip, success, createdAt ->
            dataSource.connection.use { connection ->
                connection.prepareStatement("insert into login_attempts(id, username, ip, success, created_at) values(?,?,?,?,?)").use { ps ->
                    ps.setString(1, java.util.UUID.randomUUID().toString())
                    ps.setString(2, username)
                    ps.setString(3, ip)
                    ps.setBoolean(4, success)
                    ps.setLong(5, createdAt)
                    ps.executeUpdate()
                }
            }
        }
    }
}
