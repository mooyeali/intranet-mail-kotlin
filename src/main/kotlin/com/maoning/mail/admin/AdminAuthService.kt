package com.maoning.mail.admin

import com.maoning.mail.config.AppConfig
import org.mindrot.jbcrypt.BCrypt
import org.springframework.stereotype.Service
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Service
class AdminAuthService(
    private val config: AppConfig
) {
    fun isAdminToken(token: String?): Boolean =
        config.adminToken.isNotBlank() && constantTimeEquals(token.orEmpty(), config.adminToken)

    fun isAdminAuthorized(token: String?, queryToken: String?, sessionCookie: String?): Boolean {
        if (isAdminToken(token)) return true
        if (config.adminQueryTokenEnabled && isAdminToken(queryToken)) return true
        return isAdminSession(sessionCookie)
    }

    fun passwordMatches(password: String): Boolean =
        config.adminPasswordHash.isNotBlank() && runCatching { BCrypt.checkpw(password, config.adminPasswordHash) }.getOrDefault(false)

    fun createSessionCookieValue(username: String, nowMillis: Long = System.currentTimeMillis()): String {
        require(config.adminSessionSecret.isNotBlank()) { "ADMIN_SESSION_SECRET is required for admin sessions" }
        val principal = username.trim().ifBlank { config.adminUser }
        val expiresAt = nowMillis + config.adminSessionHours * 60 * 60 * 1000
        val payload = "$principal.$expiresAt"
        val signature = sign(payload)
        return "$payload.$signature"
    }

    fun isAdminSession(cookieValue: String?, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (cookieValue.isNullOrBlank() || config.adminSessionSecret.isBlank()) return false
        val parts = cookieValue.split('.', limit = 3)
        if (parts.size != 3) return false
        val expiresAt = parts[1].toLongOrNull() ?: return false
        if (expiresAt < nowMillis) return false
        val payload = "${parts[0]}.$expiresAt"
        val expected = sign(payload)
        return constantTimeEquals(parts[2], expected)
    }

    fun createCsrfToken(sessionCookie: String): String {
        require(isAdminSession(sessionCookie)) { "valid admin session is required for csrf token" }
        return sign("csrf.$sessionCookie")
    }

    fun isValidCsrfToken(sessionCookie: String?, csrfToken: String?): Boolean {
        if (!isAdminSession(sessionCookie) || csrfToken.isNullOrBlank()) return false
        return constantTimeEquals(csrfToken, createCsrfToken(sessionCookie!!))
    }

    private fun sign(value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(config.adminSessionSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}
