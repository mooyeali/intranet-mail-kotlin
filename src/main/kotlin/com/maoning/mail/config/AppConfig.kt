package com.maoning.mail.config

data class AppConfig(
    val domain: String = env("MAIL_DOMAIN", "intra.local"),
    val httpHost: String = env("HTTP_HOST", "0.0.0.0"),
    val httpPort: Int = env("HTTP_PORT", "8080").toInt(),
    val smtpHost: String = env("SMTP_HOST", "0.0.0.0"),
    val smtpPort: Int = env("SMTP_PORT", "2525").toInt(),
    val pop3Host: String = env("POP3_HOST", "0.0.0.0"),
    val pop3Port: Int = env("POP3_PORT", "1110").toInt(),
    val h2Url: String = env("H2_URL", "jdbc:h2:./data/intranet-mail;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"),
    val h2User: String = env("H2_USER", "sa"),
    val h2Password: String = env("H2_PASSWORD", ""),
    val adminUser: String = env("ADMIN_USER", "admin"),
    val adminPasswordHash: String = env("ADMIN_PASSWORD_HASH", ""),
    val adminToken: String = env("ADMIN_TOKEN", "change-me"),
    val adminSessionSecret: String = env("ADMIN_SESSION_SECRET", "change-me"),
    val adminQueryTokenEnabled: Boolean = env("ADMIN_QUERY_TOKEN_ENABLED", "false").toBooleanStrictOrNull() ?: false,
    val secureCookies: Boolean = env("SECURE_COOKIES", "false").toBooleanStrictOrNull() ?: false,
    val maxQueueAttempts: Int = env("MAX_QUEUE_ATTEMPTS", "5").toInt(),
    val tlsKeyStore: String? = envOrNull("SMTP_TLS_KEYSTORE"),
    val tlsKeyStorePassword: String? = envOrNull("SMTP_TLS_KEYSTORE_PASSWORD"),
    val attachmentDir: String = env("ATTACHMENT_DIR", "./data/attachments"),
    val loginMaxFailures: Int = env("LOGIN_MAX_FAILURES", "5").toInt(),
    val loginWindowSeconds: Long = env("LOGIN_WINDOW_SECONDS", "300").toLong(),
    val smtpMaxConnections: Int = env("SMTP_MAX_CONNECTIONS", "50").toInt(),
    val pop3MaxConnections: Int = env("POP3_MAX_CONNECTIONS", "50").toInt(),
    val socketTimeoutMillis: Int = env("SOCKET_TIMEOUT_MILLIS", "30000").toInt(),
    val maxMessageBytes: Long = env("MAX_MESSAGE_BYTES", "10485760").toLong(),
    val maxAttachmentBytes: Long = env("MAX_ATTACHMENT_BYTES", "10485760").toLong(),
    val maxTotalAttachmentBytes: Long = env("MAX_TOTAL_ATTACHMENT_BYTES", "20971520").toLong()
) {
    fun validateForRuntime() {
        require(maxMessageBytes > 0) { "MAX_MESSAGE_BYTES must be positive" }
        require(maxAttachmentBytes > 0) { "MAX_ATTACHMENT_BYTES must be positive" }
        require(maxTotalAttachmentBytes > 0) { "MAX_TOTAL_ATTACHMENT_BYTES must be positive" }
        require(smtpMaxConnections > 0) { "SMTP_MAX_CONNECTIONS must be positive" }
        require(pop3MaxConnections > 0) { "POP3_MAX_CONNECTIONS must be positive" }
        require(socketTimeoutMillis > 0) { "SOCKET_TIMEOUT_MILLIS must be positive" }
        require(adminPasswordHash.isNotBlank() || adminToken != "change-me") {
            "Configure ADMIN_PASSWORD_HASH or replace the default ADMIN_TOKEN before starting"
        }
        require(adminSessionSecret != "change-me" && adminSessionSecret.length >= 32) {
            "Configure ADMIN_SESSION_SECRET with at least 32 characters before starting"
        }
    }
}

private fun env(key: String, default: String): String = System.getenv(key) ?: default
private fun envOrNull(key: String): String? = System.getenv(key)?.takeIf { it.isNotBlank() }
