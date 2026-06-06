package com.maoning.mail.api

import com.maoning.mail.attachment.AttachmentStorage
import com.maoning.mail.audit.AuditService
import com.maoning.mail.auth.AuthService
import com.maoning.mail.config.AppConfig
import com.maoning.mail.mail.MailService
import com.maoning.mail.mime.MimeParser
import com.maoning.mail.queue.MailQueueWorker
import com.maoning.mail.security.LoginRateLimiter
import com.maoning.mail.store.MailMessage
import com.maoning.mail.store.MailStore
import com.maoning.mail.store.QueueItem
import com.maoning.mail.store.QueueStatus
import com.maoning.mail.store.Session
import com.maoning.mail.store.User
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import java.nio.file.Path
import javax.servlet.http.HttpServletRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MailControllerSecurityTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun adminLoginWithMalformedBcryptHashReturnsUnauthorized() {
        val auditService = AuditService()
        val controller = controller(
            config = AppConfig(
                adminPasswordHash = "not-a-bcrypt-hash",
                attachmentDir = tempDir.toString()
            ),
            auditService = auditService
        )
        val servlet = FakeRequest(remoteAddrValue = "198.51.100.10")

        val response = controller.adminLogin("admin", "secret", servlet)

        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }

    @Test
    fun adminLoginSetsHttpOnlySameSiteSessionCookieWhenSessionSecretConfigured() {
        val bcryptHash = org.mindrot.jbcrypt.BCrypt.hashpw("secret", org.mindrot.jbcrypt.BCrypt.gensalt())
        val controller = controller(
            config = AppConfig(
                adminPasswordHash = bcryptHash,
                adminSessionSecret = "valid-admin-session-secret-32-chars",
                adminSessionHours = 2,
                secureCookies = true,
                attachmentDir = tempDir.toString()
            )
        )
        val servlet = FakeRequest(remoteAddrValue = "198.51.100.10")

        val response = controller.adminLogin("admin", "secret", servlet)
        val setCookie = response.headers.getFirst(HttpHeaders.SET_COOKIE).orEmpty()

        assertEquals(HttpStatus.OK, response.statusCode)
        assert(setCookie.startsWith("ADMIN_SESSION="))
        assert(setCookie.contains("HttpOnly"))
        assert(setCookie.contains("SameSite=Strict"))
        assert(setCookie.contains("Secure"))
        assert(setCookie.contains("Path=/"))
        assert(setCookie.contains("Max-Age=7200"))
    }

    @Test
    fun adminLoginRecordsFailuresAndRejectsWhenRateLimited() {
        val bcryptHash = org.mindrot.jbcrypt.BCrypt.hashpw("secret", org.mindrot.jbcrypt.BCrypt.gensalt())
        val loginRateLimiter = RecordingLoginRateLimiter()
        val controller = controller(
            config = AppConfig(
                adminPasswordHash = bcryptHash,
                attachmentDir = tempDir.toString()
            ),
            loginRateLimiter = loginRateLimiter
        )
        val servlet = FakeRequest(remoteAddrValue = "198.51.100.20")

        val failed = controller.adminLogin("admin", "wrong", servlet)
        loginRateLimiter.block = true
        val blocked = controller.adminLogin("admin", "secret", servlet)

        assertEquals(HttpStatus.UNAUTHORIZED, failed.statusCode)
        assertEquals("admin:admin", loginRateLimiter.assertedUsername)
        assertEquals("198.51.100.20", loginRateLimiter.assertedIp)
        assertEquals(listOf(false), loginRateLimiter.recordedSuccesses)
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, blocked.statusCode)
        assertEquals(listOf(false), loginRateLimiter.recordedSuccesses)
    }

    @Test
    fun clientIpIgnoresUntrustedForwardedForHeader() {
        val store = NoopMailStore("test.local")
        val loginRateLimiter = RecordingLoginRateLimiter()
        val authService = AuthService(store, loginRateLimiter)
        val auditService = AuditService()
        val controller = controller(authService = authService, store = store, auditService = auditService)
        val servlet = FakeRequest(
            remoteAddrValue = "198.51.100.10",
            headers = mapOf("X-Forwarded-For" to "203.0.113.99")
        )

        val response = controller.login(LoginRequest("alice", "bad-password"), servlet)

        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
        assertEquals("198.51.100.10", loginRateLimiter.assertedIp)
        assertEquals("198.51.100.10", loginRateLimiter.recordedIp)
    }

    @Test
    fun adminApiAcceptsQueryTokenWhenEnabled() {
        val adminToken = "valid-admin-token"
        val controller = controller(
            config = AppConfig(
                adminToken = adminToken,
                adminQueryTokenEnabled = true,
                attachmentDir = tempDir.toString()
            )
        )

        val response = controller.adminUsers(
            token = null,
            queryToken = adminToken,
            sessionCookie = null
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals("no-referrer", response.headers.getFirst("Referrer-Policy"))
        assertEquals("no-store", response.headers.getFirst(HttpHeaders.CACHE_CONTROL))
    }

    @Test
    fun adminApiRejectsQueryTokenWhenDisabled() {
        val adminToken = "valid-admin-token"
        val controller = controller(
            config = AppConfig(
                adminToken = adminToken,
                adminQueryTokenEnabled = false,
                attachmentDir = tempDir.toString()
            )
        )

        val response = controller.adminUsers(
            token = null,
            queryToken = adminToken,
            sessionCookie = null
        )

        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }

    @Test
    fun adminPostWithSessionCookieRequiresCsrfToken() {
        val config = AppConfig(
            adminToken = "valid-admin-token",
            adminSessionSecret = "valid-admin-session-secret-32-chars",
            attachmentDir = tempDir.toString()
        )
        val controller = controller(config = config)
        val session = com.maoning.mail.admin.AdminAuthService(config).createSessionCookieValue("admin")

        val response = controller.drain(
            token = null,
            queryToken = null,
            sessionCookie = session,
            csrfToken = null
        )

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    @Test
    fun adminPostWithSessionCookieAcceptsMatchingCsrfToken() {
        val config = AppConfig(
            adminToken = "valid-admin-token",
            adminSessionSecret = "valid-admin-session-secret-32-chars",
            attachmentDir = tempDir.toString()
        )
        val controller = controller(config = config)
        val adminAuthService = com.maoning.mail.admin.AdminAuthService(config)
        val session = adminAuthService.createSessionCookieValue("admin")
        val csrfToken = adminAuthService.createCsrfToken(session)

        val response = controller.drain(
            token = null,
            queryToken = null,
            sessionCookie = session,
            csrfToken = csrfToken
        )

        assertEquals(HttpStatus.OK, response.statusCode)
    }

    @Test
    fun adminPostRejectsQueryTokenEvenWhenQueryTokensAreEnabled() {
        val adminToken = "valid-admin-token"
        val controller = controller(
            config = AppConfig(
                adminToken = adminToken,
                adminQueryTokenEnabled = true,
                attachmentDir = tempDir.toString()
            )
        )

        val response = controller.drain(
            token = null,
            queryToken = adminToken,
            sessionCookie = null,
            csrfToken = null
        )

        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }

    @Test
    fun sendRejectsOversizedBodyBeforeAuthentication() {
        val store = NoopMailStore("test.local")
        val authService = AuthService(store)
        val mailService = MailService(store, MimeParser(AttachmentStorage(tempDir.toString())), AuditService())
        val controller = controller(
            config = AppConfig(maxMessageBytes = 5, attachmentDir = tempDir.toString()),
            authService = authService,
            mailService = mailService,
            store = store
        )

        val response = controller.send("Bearer token", SendMailRequest(listOf("bob"), "subject", "too long"))

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.statusCode)
        assertFalse(store.findSessionCalled)
        assertFalse(store.saveMessageCalled)
    }

    @Test
    fun sendRejectsTooManyRecipientsBeforeAuthentication() {
        val store = NoopMailStore("test.local")
        val controller = controller(
            config = AppConfig(maxRecipients = 2, attachmentDir = tempDir.toString()),
            authService = AuthService(store),
            store = store
        )

        val response = controller.send("Bearer token", SendMailRequest(listOf("a", "b", "c"), "subject", "body"))

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertFalse(store.findSessionCalled)
        assertFalse(store.saveMessageCalled)
    }

    private fun controller(
        config: AppConfig = AppConfig(attachmentDir = tempDir.toString()),
        authService: AuthService = AuthService(NoopMailStore(config.domain)),
        mailService: MailService = MailService(NoopMailStore(config.domain), MimeParser(AttachmentStorage(config.attachmentDir)), AuditService()),
        store: MailStore = NoopMailStore(config.domain),
        queueWorker: MailQueueWorker = MailQueueWorker(store, config),
        auditService: AuditService = AuditService(),
        attachmentStorage: AttachmentStorage = AttachmentStorage(config.attachmentDir),
        loginRateLimiter: LoginRateLimiter? = null
    ) = MailController(config, authService, mailService, store, queueWorker, auditService, attachmentStorage, com.maoning.mail.admin.AdminAuthService(config), loginRateLimiter)
}

private class FakeRequest(
    private val remoteAddrValue: String,
    private val headers: Map<String, String> = emptyMap()
) : org.springframework.mock.web.MockHttpServletRequest() {
    override fun getRemoteAddr(): String = remoteAddrValue
    override fun getHeader(name: String): String? = headers[name]
}

private class RecordingLoginRateLimiter : LoginRateLimiter(AppConfig()) {
    var assertedIp: String? = null
    var assertedUsername: String? = null
    var recordedIp: String? = null
    val recordedSuccesses = mutableListOf<Boolean>()
    var block = false

    override fun assertAllowed(username: String, ip: String) {
        if (block) error("Too many failed login attempts, try later")
        assertedUsername = username
        assertedIp = ip
    }

    override fun record(username: String, ip: String, success: Boolean) {
        recordedIp = ip
        recordedSuccesses += success
    }
}

private class NoopMailStore(override val domain: String) : MailStore {
    var findSessionCalled = false
    var saveMessageCalled = false

    override fun normalizeMailbox(input: String): String = if ('@' in input) input else "$input@$domain"
    override fun userExists(mailbox: String): Boolean = true
    override fun createUser(user: User): User = user
    override fun findUser(mailbox: String): User? = null
    override fun saveSession(session: Session): Session = session
    override fun findSession(token: String): Session? {
        findSessionCalled = true
        return null
    }
    override fun saveMessage(message: MailMessage): MailMessage {
        saveMessageCalled = true
        return message
    }
    override fun queueRecipients(messageId: String, recipients: List<String>) {}
    override fun inbox(mailbox: String, limit: Int, offset: Int): List<MailMessage> = emptyList()
    override fun sent(mailbox: String, limit: Int, offset: Int): List<MailMessage> = emptyList()
    override fun archive(mailbox: String, limit: Int, offset: Int): List<MailMessage> = emptyList()
    override fun trash(mailbox: String, limit: Int, offset: Int): List<MailMessage> = emptyList()
    override fun findMessageForMailbox(messageId: String, mailbox: String, includeDeleted: Boolean): MailMessage? = null
    override fun mailboxState(mailbox: String, messageId: String, includeDeleted: Boolean): com.maoning.mail.store.MailboxState? = null
    override fun search(mailbox: String, query: String, box: String?, limit: Int): List<MailMessage> = emptyList()
    override fun setArchived(mailbox: String, messageId: String, archived: Boolean) {}
    override fun setDeleted(mailbox: String, messageId: String, deleted: Boolean) {}
    override fun markRead(mailbox: String, messageId: String, read: Boolean) {}
    override fun users(): List<User> = emptyList()
    override fun messages(limit: Int): List<MailMessage> = emptyList()
    override fun queueItems(status: QueueStatus?): List<QueueItem> = emptyList()
    override fun nextQueued(limit: Int): List<QueueItem> = emptyList()
    override fun markQueueInProgress(id: String, attempts: Int): Boolean = false
    override fun markQueueDelivered(id: String) {}
    override fun markQueueRetry(id: String, error: String, nextAttemptAt: Long, attempts: Int) {}
    override fun markQueueDead(id: String, error: String, attempts: Int) {}
    override fun deliverQueued(item: QueueItem) {}
}
