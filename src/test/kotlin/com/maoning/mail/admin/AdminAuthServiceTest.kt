package com.maoning.mail.admin

import com.maoning.mail.config.AppConfig
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AdminAuthServiceTest {
    @Test
    fun recognizesValidAdminToken() {
        val auth = AdminAuthService(AppConfig(adminToken = "valid-token"))

        assertTrue(auth.isAdminToken("valid-token"))
        assertFalse(auth.isAdminToken("bad-token"))
        assertFalse(auth.isAdminToken(null))
    }

    @Test
    fun supportsSignedAdminSessionCookie() {
        val auth = AdminAuthService(AppConfig(adminSessionSecret = "valid-admin-session-secret-32-chars"))
        val cookie = auth.createSessionCookieValue("admin")

        assertTrue(auth.isAdminSession(cookie))
        assertFalse(auth.isAdminSession(cookie.replace("admin", "root")))
    }

    @Test
    fun sessionCookieIncludesExpiryAndExpires() {
        val auth = AdminAuthService(AppConfig(adminSessionSecret = "valid-admin-session-secret-32-chars", adminSessionHours = 1))
        val now = 1_000L
        val cookie = auth.createSessionCookieValue("admin", now)

        assertTrue(auth.isAdminSession(cookie, now + 3_599_999L))
        assertFalse(auth.isAdminSession(cookie, now + 3_600_001L))
    }

    @Test
    fun signingSameUserAtDifferentTimesProducesDifferentCookies() {
        val auth = AdminAuthService(AppConfig(adminSessionSecret = "valid-admin-session-secret-32-chars", adminSessionHours = 1))

        assertNotEquals(auth.createSessionCookieValue("admin", 1_000L), auth.createSessionCookieValue("admin", 2_000L))
    }

    @Test
    fun authorizedWhenHeaderTokenProvided() {
        val auth = AdminAuthService(
            AppConfig(
                adminToken = "token-1",
                adminQueryTokenEnabled = true,
                adminSessionSecret = "valid-admin-session-secret-32-chars"
            )
        )

        assertTrue(auth.isAdminAuthorized("token-1", "wrong", null))
    }

    @Test
    fun authorizedWithQueryTokenOnlyWhenEnabled() {
        val auth = AdminAuthService(
            AppConfig(
                adminToken = "token-1",
                adminQueryTokenEnabled = true,
                adminSessionSecret = "valid-admin-session-secret-32-chars"
            )
        )

        assertTrue(auth.isAdminAuthorized(null, "token-1", null))
    }

    @Test
    fun unauthorizedWithQueryTokenWhenDisabled() {
        val auth = AdminAuthService(
            AppConfig(
                adminToken = "token-1",
                adminQueryTokenEnabled = false,
                adminSessionSecret = "valid-admin-session-secret-32-chars"
            )
        )

        assertFalse(auth.isAdminAuthorized(null, "token-1", null))
    }
}
