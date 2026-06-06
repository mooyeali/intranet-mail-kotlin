package com.maoning.mail.admin

import com.maoning.mail.audit.AuditService
import com.maoning.mail.config.AppConfig
import com.maoning.mail.store.MailMessage
import com.maoning.mail.store.MailStore
import com.maoning.mail.store.QueueItem
import com.maoning.mail.store.QueueStatus
import com.maoning.mail.store.Session
import com.maoning.mail.store.User
import org.springframework.ui.ConcurrentModel
import kotlin.test.Test
import kotlin.test.assertEquals

class AdminViewControllerTest {
    @Test
    fun adminDashboardReturnsThymeleafTemplateWhenTokenIsValid() {
        val config = AppConfig(domain = "example.test", adminToken = "valid-token")
        val controller = AdminViewController(AdminAuthService(config), config, StubMailStore("example.test"), AuditService())
        val model = ConcurrentModel()

        val view = controller.dashboard("valid-token", null, null, model)

        assertEquals("admin/dashboard", view)
        assertEquals("example.test", model["domain"])
        assertEquals(1, model["userCount"])
        assertEquals(2, model["messageCount"])
        assertEquals(3, model["queueCount"])
    }

    @Test
    fun adminDashboardReturnsLoginTemplateWhenTokenIsInvalid() {
        val config = AppConfig(domain = "example.test", adminToken = "valid-token")
        val controller = AdminViewController(AdminAuthService(config), config, StubMailStore("example.test"), AuditService())
        val model = ConcurrentModel()

        val view = controller.dashboard("bad-token", null, null, model)

        assertEquals("admin/login", view)
    }

    @Test
    fun adminLoginReturnsThymeleafTemplate() {
        val config = AppConfig(domain = "example.test")
        val controller = AdminViewController(AdminAuthService(config), config, StubMailStore("example.test"), AuditService())
        val model = ConcurrentModel()

        val view = controller.login(model)

        assertEquals("admin/login", view)
        assertEquals("Admin Login", model["title"])
    }

    @Test
    fun adminDashboardAcceptsSignedSessionCookie() {
        val config = AppConfig(domain = "example.test", adminSessionSecret = "valid-admin-session-secret-32-chars")
        val auth = AdminAuthService(config)
        val controller = AdminViewController(auth, config, StubMailStore("example.test"), AuditService())
        val model = ConcurrentModel()

        val view = controller.dashboard(null, null, auth.createSessionCookieValue("admin"), model)

        assertEquals("admin/dashboard", view)
    }
}

private class StubMailStore(override val domain: String) : MailStore {
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
    override fun users(): List<User> = listOf(User(username = "alice", mailbox = "alice@$domain", passwordHash = "hash"))
    override fun messages(limit: Int): List<MailMessage> = listOf(
        MailMessage(from = "alice@$domain", to = listOf("bob@$domain"), subject = "one", body = "body"),
        MailMessage(from = "bob@$domain", to = listOf("alice@$domain"), subject = "two", body = "body")
    )
    override fun queueItems(status: QueueStatus?): List<QueueItem> = listOf(
        QueueItem(messageId = "m1", recipient = "a@$domain"),
        QueueItem(messageId = "m2", recipient = "b@$domain"),
        QueueItem(messageId = "m3", recipient = "c@$domain")
    )
    override fun nextQueued(limit: Int): List<QueueItem> = emptyList()
    override fun markQueueInProgress(id: String, attempts: Int): Boolean = false
    override fun markQueueDelivered(id: String) {}
    override fun markQueueRetry(id: String, error: String, nextAttemptAt: Long, attempts: Int) {}
    override fun markQueueDead(id: String, error: String, attempts: Int) {}
    override fun deliverQueued(item: QueueItem) {}
}
