package com.maoning.mail.config

import kotlin.test.Test
import kotlin.test.assertFailsWith

class AppConfigTest {
    @Test
    fun rejectsDefaultAdminTokenForRuntime() {
        val config = AppConfig(
            adminToken = "change-me",
            adminPasswordHash = "",
            adminSessionSecret = "valid-admin-session-secret-32-chars"
        )
        assertFailsWith<IllegalArgumentException> { config.validateForRuntime() }
    }

    @Test
    fun rejectsDefaultAdminSessionSecretForRuntime() {
        val config = AppConfig(
            adminToken = "valid-admin-token",
            adminPasswordHash = "",
            adminSessionSecret = "change-me"
        )
        assertFailsWith<IllegalArgumentException> { config.validateForRuntime() }
    }

    @Test
    fun rejectsMalformedAdminPasswordHashForRuntime() {
        val config = AppConfig(
            adminToken = "",
            adminPasswordHash = "not-a-bcrypt-hash",
            adminSessionSecret = "valid-admin-session-secret-32-chars"
        )
        assertFailsWith<IllegalArgumentException> { config.validateForRuntime() }
    }
}
