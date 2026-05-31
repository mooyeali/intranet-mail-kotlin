package com.maoning.mail.config

import kotlin.test.Test
import kotlin.test.assertFailsWith

class AppConfigTest {
    @Test
    fun rejectsDefaultAdminSecretsForRuntime() {
        val config = AppConfig(adminToken = "change-me", adminPasswordHash = "")
        assertFailsWith<IllegalArgumentException> { config.validateForRuntime() }
    }
}
