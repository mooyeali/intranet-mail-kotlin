package com.maoning.mail.api

import com.maoning.mail.admin.AdminAuthService
import com.maoning.mail.attachment.AttachmentStorage
import com.maoning.mail.audit.AuditService
import com.maoning.mail.auth.AuthService
import com.maoning.mail.config.AppConfig
import com.maoning.mail.mail.MailService
import com.maoning.mail.mime.MimeParser
import com.maoning.mail.queue.MailQueueWorker
import com.maoning.mail.security.LoginRateLimiter
import com.maoning.mail.store.Attachment
import com.maoning.mail.store.MailMessage
import com.maoning.mail.store.MailStore
import com.maoning.mail.store.MailboxState
import com.maoning.mail.store.QueueItem
import com.maoning.mail.store.QueueStatus
import com.maoning.mail.store.Session
import com.maoning.mail.store.User
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.nio.file.Path
import kotlin.test.Test

class MailControllerMockMvcTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun healthAndDnsRoutesReturnDomainReadinessAndDnsRecords() {
        val mvc = mockMvc(config = AppConfig(domain = "corp.test", smtpPort = 2525, pop3Port = 1110, attachmentDir = tempDir.toString()))

        mvc.get("/health")
            .andExpect { status { isOk() }; jsonPath("$.status") { value("ok") }; jsonPath("$.domain") { value("corp.test") } }
        mvc.get("/health/live")
            .andExpect { status { isOk() }; jsonPath("$.status") { value("ok") } }
        mvc.get("/health/ready")
            .andExpect { status { isOk() }; jsonPath("$.status") { value("ready") }; jsonPath("$.checks.store") { value("ok") } }
        mvc.get("/dns")
            .andExpect {
                status { isOk() }
                content { contentTypeCompatibleWith(MediaType.TEXT_PLAIN) }
                content { string(org.hamcrest.Matchers.containsString("corp.test.        IN MX 10 mail.corp.test.")) }
                content { string(org.hamcrest.Matchers.containsString("_smtp._tcp.corp.test. IN SRV 0 5 2525 mail.corp.test.")) }
                content { string(org.hamcrest.Matchers.containsString("_pop3._tcp.corp.test. IN SRV 0 5 1110 mail.corp.test.")) }
            }
    }

    @Test
    fun inboxSentAndSearchRequireBearerAuthAndUseAuthenticatedMailbox() {
        val inboxMessage = MailMessage(id = "in-1", from = "alice@test.local", to = listOf("bob@test.local"), subject = "Inbox", body = "body")
        val sentMessage = MailMessage(id = "sent-1", from = "bob@test.local", to = listOf("alice@test.local"), subject = "Sent", body = "body")
        val searchMessage = MailMessage(id = "search-1", from = "alice@test.local", to = listOf("bob@test.local"), subject = "Search", body = "body")
        val store = MockMvcMailStore(
            inboxMessages = listOf(inboxMessage),
            sentMessages = listOf(sentMessage),
            searchMessages = listOf(searchMessage)
        )
        val mvc = mockMvc(store = store)

        mvc.get("/api/mail/inbox")
            .andExpect { status { isUnauthorized() }; jsonPath("$.error") { value("missing or invalid token") } }
        mvc.get("/api/mail/sent")
            .andExpect { status { isUnauthorized() } }
        mvc.get("/api/mail/search") { param("q", "hello") }
            .andExpect { status { isUnauthorized() } }

        mvc.get("/api/mail/inbox") { header(HttpHeaders.AUTHORIZATION, "Bearer good-token") }
            .andExpect { status { isOk() }; jsonPath("$[0].id") { value("in-1") } }
        mvc.get("/api/mail/sent") { header(HttpHeaders.AUTHORIZATION, "Bearer good-token") }
            .andExpect { status { isOk() }; jsonPath("$[0].id") { value("sent-1") } }
        mvc.get("/api/mail/search") {
            header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
            param("q", "hello")
            param("box", "trash")
            param("limit", "999")
        }.andExpect { status { isOk() }; jsonPath("$[0].id") { value("search-1") } }

        kotlin.test.assertEquals(listOf("bob@test.local"), store.inboxCalls)
        kotlin.test.assertEquals(listOf("bob@test.local"), store.sentCalls)
        kotlin.test.assertEquals(listOf(SearchCall("bob@test.local", "hello", null, 500)), store.searchCalls)
    }

    @Test
    fun searchClampsLowerLimitAndPreservesAllowedMailboxBox() {
        val store = MockMvcMailStore(searchMessages = listOf(MailMessage(id = "search-1", from = "alice@test.local", to = listOf("bob@test.local"), subject = "Search", body = "body")))
        val mvc = mockMvc(store = store)

        mvc.get("/api/mail/search") {
            header(HttpHeaders.AUTHORIZATION, "Bearer good-token")
            param("q", "hello")
            param("box", "sent")
            param("limit", "0")
        }.andExpect { status { isOk() }; jsonPath("$[0].id") { value("search-1") } }

        kotlin.test.assertEquals(listOf(SearchCall("bob@test.local", "hello", "sent", 1)), store.searchCalls)
    }

    @Test
    fun attachmentDownloadRequiresOwnerAndReturnsNotFoundVariantsAndContentDisposition() {
        val attachmentStorage = AttachmentStorage(tempDir.toString())
        val stored = attachmentStorage.save("hello attachment".toByteArray(), "report.txt")
        val ownedMessage = MailMessage(
            id = "m1",
            from = "alice@test.local",
            to = listOf("bob@test.local"),
            subject = "With attachment",
            body = "body",
            attachments = listOf(Attachment(id = "a1", fileName = "quarterly\"report.txt", contentType = "text/plain", size = 16, path = stored.path))
        )
        val noFileMessage = ownedMessage.copy(id = "m2", attachments = listOf(Attachment(id = "nofile", fileName = "nofile.txt", contentType = "text/plain", size = 0, path = null)))
        val store = MockMvcMailStore(messagesByMailbox = mutableMapOf(
            "bob@test.local:m1:false" to ownedMessage,
            "bob@test.local:m2:false" to noFileMessage
        ))
        val mvc = mockMvc(store = store, attachmentStorage = attachmentStorage)

        mvc.get("/api/mail/m1/attachments/a1")
            .andExpect { status { isUnauthorized() }; jsonPath("$.error") { value("missing or invalid token") } }
        mvc.get("/api/mail/missing/attachments/a1") { header(HttpHeaders.AUTHORIZATION, "Bearer good-token") }
            .andExpect { status { isNotFound() }; jsonPath("$.error") { value("message not found") } }
        mvc.get("/api/mail/m1/attachments/missing") { header(HttpHeaders.AUTHORIZATION, "Bearer good-token") }
            .andExpect { status { isNotFound() }; jsonPath("$.error") { value("attachment not found") } }
        mvc.get("/api/mail/m2/attachments/nofile") { header(HttpHeaders.AUTHORIZATION, "Bearer good-token") }
            .andExpect { status { isNotFound() }; jsonPath("$.error") { value("attachment has no file") } }
        mvc.get("/api/mail/m1/attachments/a1") { header(HttpHeaders.AUTHORIZATION, "Bearer other-token") }
            .andExpect { status { isNotFound() }; jsonPath("$.error") { value("message not found") } }
        mvc.get("/api/mail/m1/attachments/a1") { header(HttpHeaders.AUTHORIZATION, "Bearer good-token") }
            .andExpect {
                status { isOk() }
                header { string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"quarterly_report.txt\"") }
                content { string("hello attachment") }
            }
    }

    @Test
    fun registrationLoginValidationAndRateLimitAreHttpVisible() {
        val user = User(username = "bob", mailbox = "bob@test.local", passwordHash = org.mindrot.jbcrypt.BCrypt.hashpw("correct-password", org.mindrot.jbcrypt.BCrypt.gensalt()))
        val limiter = RecordingHttpLoginRateLimiter()
        val store = MockMvcMailStore(usersByLogin = mutableMapOf("bob" to user, "bob@test.local" to user))
        val mvc = mockMvc(store = store, authService = AuthService(store, limiter), loginRateLimiter = limiter)

        mvc.post("/api/register") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"username":"bo","password":"password123"}"""
        }.andExpect { status { isBadRequest() }; jsonPath("$.error") { value(org.hamcrest.Matchers.containsString("Username must be 3-32 chars")) } }

        mvc.post("/api/register") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"username":"charlie","password":"password123"}"""
            with { it.remoteAddr = "198.51.100.24"; it }
        }.andExpect { status { isCreated() }; jsonPath("$.username") { value("charlie") }; jsonPath("$.mailbox") { value("charlie@test.local") } }

        mvc.post("/api/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"username":"bob","password":"wrong-password"}"""
            with { it.remoteAddr = "198.51.100.25"; it }
        }.andExpect { status { isUnauthorized() }; jsonPath("$.error") { value("Invalid credentials") } }

        limiter.block = true
        mvc.post("/api/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"username":"bob","password":"correct-password"}"""
            with { it.remoteAddr = "198.51.100.25"; it }
        }.andExpect { status { isUnauthorized() }; jsonPath("$.error") { value("Too many failed login attempts, try later") } }

        kotlin.test.assertEquals(listOf(LoginCheck("bob", "198.51.100.25"), LoginCheck("bob", "198.51.100.25")), limiter.asserted)
        kotlin.test.assertEquals(listOf(false), limiter.recordedSuccesses)
    }

    private fun mockMvc(
        config: AppConfig = AppConfig(domain = "test.local", attachmentDir = tempDir.toString()),
        store: MockMvcMailStore = MockMvcMailStore(domainName = config.domain),
        attachmentStorage: AttachmentStorage = AttachmentStorage(config.attachmentDir),
        auditService: AuditService = AuditService(),
        authService: AuthService = AuthService(store),
        loginRateLimiter: LoginRateLimiter? = null
    ): MockMvc {
        val controller = MailController(
            config,
            authService,
            MailService(store, MimeParser(attachmentStorage), auditService),
            store,
            MailQueueWorker(store, config),
            auditService,
            attachmentStorage,
            AdminAuthService(config),
            loginRateLimiter
        )
        return MockMvcBuilders.standaloneSetup(controller).build()
    }
}

data class SearchCall(val mailbox: String, val query: String, val box: String?, val limit: Int)
data class LoginCheck(val username: String, val ip: String)

private class RecordingHttpLoginRateLimiter : LoginRateLimiter(AppConfig()) {
    val asserted = mutableListOf<LoginCheck>()
    val recordedSuccesses = mutableListOf<Boolean>()
    var block = false

    override fun assertAllowed(username: String, ip: String) {
        asserted += LoginCheck(username, ip)
        if (block) error("Too many failed login attempts, try later")
    }

    override fun record(username: String, ip: String, success: Boolean) {
        recordedSuccesses += success
    }
}

private class MockMvcMailStore(
    private val domainName: String = "test.local",
    private val inboxMessages: List<MailMessage> = emptyList(),
    private val sentMessages: List<MailMessage> = emptyList(),
    private val searchMessages: List<MailMessage> = emptyList(),
    private val messagesByMailbox: MutableMap<String, MailMessage> = mutableMapOf(),
    private val usersByLogin: MutableMap<String, User> = mutableMapOf()
) : MailStore {
    override val domain: String = domainName
    val inboxCalls = mutableListOf<String>()
    val sentCalls = mutableListOf<String>()
    val searchCalls = mutableListOf<SearchCall>()

    override fun normalizeMailbox(input: String): String = if ('@' in input) input.trim().lowercase() else "${input.trim().lowercase()}@$domain"
    override fun userExists(mailbox: String): Boolean = usersByLogin.values.any { it.mailbox == normalizeMailbox(mailbox) }
    override fun createUser(user: User): User {
        usersByLogin[user.username] = user
        usersByLogin[user.mailbox] = user
        return user
    }
    override fun findUser(mailbox: String): User? = usersByLogin[mailbox] ?: usersByLogin[normalizeMailbox(mailbox)]
    override fun saveSession(session: Session): Session = session
    override fun findSession(token: String): Session? = when (token) {
        "good-token" -> Session(token, "bob@test.local")
        "other-token" -> Session(token, "alice@test.local")
        else -> null
    }
    override fun saveMessage(message: MailMessage): MailMessage = message
    override fun queueRecipients(messageId: String, recipients: List<String>) {}
    override fun inbox(mailbox: String, limit: Int, offset: Int): List<MailMessage> {
        inboxCalls += normalizeMailbox(mailbox)
        return inboxMessages
    }
    override fun sent(mailbox: String, limit: Int, offset: Int): List<MailMessage> {
        sentCalls += normalizeMailbox(mailbox)
        return sentMessages
    }
    override fun archive(mailbox: String, limit: Int, offset: Int): List<MailMessage> = emptyList()
    override fun trash(mailbox: String, limit: Int, offset: Int): List<MailMessage> = emptyList()
    override fun findMessageForMailbox(messageId: String, mailbox: String, includeDeleted: Boolean): MailMessage? = messagesByMailbox["${normalizeMailbox(mailbox)}:$messageId:$includeDeleted"]
    override fun mailboxState(mailbox: String, messageId: String, includeDeleted: Boolean): MailboxState? = null
    override fun search(mailbox: String, query: String, box: String?, limit: Int): List<MailMessage> {
        searchCalls += SearchCall(normalizeMailbox(mailbox), query, box, limit)
        return searchMessages
    }
    override fun setArchived(mailbox: String, messageId: String, archived: Boolean) {}
    override fun setDeleted(mailbox: String, messageId: String, deleted: Boolean) {}
    override fun markRead(mailbox: String, messageId: String, read: Boolean) {}
    override fun users(): List<User> = usersByLogin.values.distinctBy { it.mailbox }
    override fun messages(limit: Int): List<MailMessage> = emptyList()
    override fun queueItems(status: QueueStatus?): List<QueueItem> = emptyList()
    override fun nextQueued(limit: Int): List<QueueItem> = emptyList()
    override fun markQueueInProgress(id: String, attempts: Int): Boolean = false
    override fun markQueueDelivered(id: String) {}
    override fun markQueueRetry(id: String, error: String, nextAttemptAt: Long, attempts: Int) {}
    override fun markQueueDead(id: String, error: String, attempts: Int) {}
    override fun deliverQueued(item: QueueItem) {}
}
