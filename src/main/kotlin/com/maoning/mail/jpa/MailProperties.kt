package com.maoning.mail.jpa

import com.maoning.mail.config.AppConfig
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "mail")
class MailProperties {
    var domain: String = env("MAIL_DOMAIN", "intra.local")
    var httpHost: String = env("HTTP_HOST", "0.0.0.0")
    var httpPort: Int = env("HTTP_PORT", "8080").toInt()
    var smtpHost: String = env("SMTP_HOST", "0.0.0.0")
    var smtpPort: Int = env("SMTP_PORT", "2525").toInt()
    var pop3Host: String = env("POP3_HOST", "0.0.0.0")
    var pop3Port: Int = env("POP3_PORT", "1110").toInt()
    var h2Url: String = env("H2_URL", "jdbc:h2:./data/intranet-mail;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE")
    var h2User: String = env("H2_USER", "sa")
    var h2Password: String = env("H2_PASSWORD", "")
    var adminUser: String = env("ADMIN_USER", "admin")
    var adminPasswordHash: String = env("ADMIN_PASSWORD_HASH", "")
    var adminToken: String = env("ADMIN_TOKEN", "")
    var adminSessionSecret: String = env("ADMIN_SESSION_SECRET", "")
    var adminSessionHours: Long = env("ADMIN_SESSION_HOURS", "8").toLong()
    var adminQueryTokenEnabled: Boolean = env("ADMIN_QUERY_TOKEN_ENABLED", "false").toBooleanStrictOrNull() ?: false
    var secureCookies: Boolean = env("SECURE_COOKIES", "false").toBooleanStrictOrNull() ?: false
    var maxQueueAttempts: Int = env("MAX_QUEUE_ATTEMPTS", "5").toInt()
    var tlsKeyStore: String? = envOrNull("SMTP_TLS_KEYSTORE")
    var tlsKeyStorePassword: String? = envOrNull("SMTP_TLS_KEYSTORE_PASSWORD")
    var smtpRequireTlsForAuth: Boolean = env("SMTP_REQUIRE_TLS_FOR_AUTH", "true").toBooleanStrictOrNull() ?: true
    var pop3RequireTlsForAuth: Boolean = env("POP3_REQUIRE_TLS_FOR_AUTH", "true").toBooleanStrictOrNull() ?: true
    var trustedProxyCidrs: List<String> = env("TRUSTED_PROXY_CIDRS", "").split(',').map { it.trim() }.filter { it.isNotBlank() }
    var attachmentDir: String = env("ATTACHMENT_DIR", "./data/attachments")
    var loginMaxFailures: Int = env("LOGIN_MAX_FAILURES", "5").toInt()
    var loginWindowSeconds: Long = env("LOGIN_WINDOW_SECONDS", "300").toLong()
    var smtpMaxConnections: Int = env("SMTP_MAX_CONNECTIONS", "50").toInt()
    var pop3MaxConnections: Int = env("POP3_MAX_CONNECTIONS", "50").toInt()
    var socketTimeoutMillis: Int = env("SOCKET_TIMEOUT_MILLIS", "30000").toInt()
    var maxMessageBytes: Long = env("MAX_MESSAGE_BYTES", "10485760").toLong()
    var maxAttachmentBytes: Long = env("MAX_ATTACHMENT_BYTES", "10485760").toLong()
    var maxTotalAttachmentBytes: Long = env("MAX_TOTAL_ATTACHMENT_BYTES", "20971520").toLong()
    var maxRecipients: Int = env("MAX_RECIPIENTS", "100").toInt()
    var socketServersEnabled: Boolean = env("SOCKET_SERVERS_ENABLED", "true").toBooleanStrictOrNull() ?: true

    fun toAppConfig(): AppConfig = AppConfig(
        domain = domain,
        httpHost = httpHost,
        httpPort = httpPort,
        smtpHost = smtpHost,
        smtpPort = smtpPort,
        pop3Host = pop3Host,
        pop3Port = pop3Port,
        h2Url = h2Url,
        h2User = h2User,
        h2Password = h2Password,
        adminUser = adminUser,
        adminPasswordHash = adminPasswordHash,
        adminToken = adminToken,
        adminSessionSecret = adminSessionSecret,
        adminSessionHours = adminSessionHours,
        adminQueryTokenEnabled = adminQueryTokenEnabled,
        secureCookies = secureCookies,
        maxQueueAttempts = maxQueueAttempts,
        tlsKeyStore = tlsKeyStore,
        tlsKeyStorePassword = tlsKeyStorePassword,
        smtpRequireTlsForAuth = smtpRequireTlsForAuth,
        pop3RequireTlsForAuth = pop3RequireTlsForAuth,
        trustedProxyCidrs = trustedProxyCidrs,
        attachmentDir = attachmentDir,
        loginMaxFailures = loginMaxFailures,
        loginWindowSeconds = loginWindowSeconds,
        smtpMaxConnections = smtpMaxConnections,
        pop3MaxConnections = pop3MaxConnections,
        socketTimeoutMillis = socketTimeoutMillis,
        maxMessageBytes = maxMessageBytes,
        maxAttachmentBytes = maxAttachmentBytes,
        maxTotalAttachmentBytes = maxTotalAttachmentBytes,
        maxRecipients = maxRecipients
    )

    fun validateForRuntime() = toAppConfig().validateForRuntime()
}

private fun env(key: String, default: String): String = System.getenv(key) ?: default
private fun envOrNull(key: String): String? = System.getenv(key)?.takeIf { it.isNotBlank() }
