package com.maoning.mail.api

import com.maoning.mail.attachment.AttachmentStorage
import com.maoning.mail.audit.AuditService
import com.maoning.mail.auth.AuthService
import com.maoning.mail.config.AppConfig
import com.maoning.mail.mail.MailService
import com.maoning.mail.mime.MimeParser
import com.maoning.mail.queue.MailQueueWorker
import com.maoning.mail.store.Attachment
import com.maoning.mail.store.MailMessage
import com.maoning.mail.store.MailStore
import com.maoning.mail.store.QueueItem
import com.maoning.mail.store.QueueStatus
import com.maoning.mail.store.Session
import com.maoning.mail.store.User
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ObservabilityTest {
    @TempDir lateinit var tempDir: Path

    @Test
    fun livenessAndReadinessExposeSeparatedHealthSignals() {
        val store = ObservabilityStore("test.local")
        val controller = controller(store)

        assertEquals("ok", controller.liveness().status)
        val ready = controller.readiness()

        assertEquals(HttpStatus.OK, ready.statusCode)
        val body = ready.body as ReadinessResponse
        assertEquals("ready", body.status)
        assertEquals("ok", body.checks["store"])
    }

    @Test
    fun readinessFailsWhenStoreCheckFails() {
        val store = ObservabilityStore("test.local", failStoreCheck = true)
        val controller = controller(store)

        val response = controller.readiness()

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.statusCode)
        val body = response.body as ReadinessResponse
        assertEquals("not-ready", body.status)
    }

    @Test
    fun queueMetricsExposeQueueAndDeadLetterCounts() {
        val store = ObservabilityStore("test.local", queue = listOf(
            queueItem("1", QueueStatus.QUEUED),
            queueItem("2", QueueStatus.RETRY),
            queueItem("3", QueueStatus.DEAD),
            queueItem("4", QueueStatus.DEAD),
        ))
        val metrics = controller(store).queueMetrics()

        assertEquals(1, metrics.queued)
        assertEquals(1, metrics.retry)
        assertEquals(0, metrics.delivered)
        assertEquals(2, metrics.dead)
        assertEquals(4, metrics.total)
    }

    @Test
    fun correlationIdFilterReturnsExistingOrGeneratedCorrelationId() {
        val filter = CorrelationIdFilter()
        val request = MockHttpServletRequest("GET", "/health")
        request.addHeader("X-Correlation-Id", "trace-123")
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        assertEquals("trace-123", response.getHeader("X-Correlation-Id"))

        val generatedRequest = MockHttpServletRequest("GET", "/health")
        val generatedResponse = MockHttpServletResponse()
        filter.doFilter(generatedRequest, generatedResponse, MockFilterChain())
        assertNotNull(generatedResponse.getHeader("X-Correlation-Id"))
    }

    private fun controller(store: MailStore): MailController {
        val config = AppConfig(domain = store.domain, attachmentDir = tempDir.toString())
        val auditService = AuditService()
        val attachmentStorage = AttachmentStorage(config.attachmentDir)
        val mimeParser = MimeParser(attachmentStorage)
        return MailController(
            config,
            AuthService(store),
            MailService(store, mimeParser, auditService),
            store,
            MailQueueWorker(store, config),
            auditService,
            attachmentStorage,
            com.maoning.mail.admin.AdminAuthService(config)
        )
    }
}

private fun queueItem(id: String, status: QueueStatus) = QueueItem(
    id = id,
    messageId = "message-$id",
    recipient = "user@test.local",
    status = status,
    attempts = 0,
    lastError = null,
    nextAttemptAt = 0,
    createdAt = 0,
    updatedAt = 0
)

private class ObservabilityStore(
    override val domain: String,
    private val failStoreCheck: Boolean = false,
    private val queue: List<QueueItem> = emptyList()
) : MailStore {
    override fun normalizeMailbox(input: String): String = if ('@' in input) input else "$input@$domain"
    override fun userExists(mailbox: String): Boolean = true
    override fun createUser(user: User): User = user
    override fun findUser(mailbox: String): User? = null
    override fun saveSession(session: Session): Session = session
    override fun findSession(token: String): Session? = null
    override fun saveMessage(message: MailMessage): MailMessage = message
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
    override fun users(): List<User> {
        if (failStoreCheck) error("store unavailable")
        return emptyList()
    }
    override fun messages(limit: Int): List<MailMessage> = emptyList()
    override fun queueItems(status: QueueStatus?): List<QueueItem> {
        if (failStoreCheck) error("store unavailable")
        return status?.let { queue.filter { item -> item.status == it } } ?: queue
    }
    override fun nextQueued(limit: Int): List<QueueItem> = emptyList()
    override fun markQueueInProgress(id: String, attempts: Int): Boolean = false
    override fun markQueueDelivered(id: String) {}
    override fun markQueueRetry(id: String, error: String, nextAttemptAt: Long, attempts: Int) {}
    override fun markQueueDead(id: String, error: String, attempts: Int) {}
    override fun deliverQueued(item: QueueItem) {}
}
