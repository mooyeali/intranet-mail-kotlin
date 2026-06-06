package com.maoning.mail.queue

import com.maoning.mail.config.AppConfig
import com.maoning.mail.store.Attachment
import com.maoning.mail.store.MailMessage
import com.maoning.mail.store.MailStore
import com.maoning.mail.store.MailboxState
import com.maoning.mail.store.QueueItem
import com.maoning.mail.store.QueueStatus
import com.maoning.mail.store.Session
import com.maoning.mail.store.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MailQueueWorkerEdgeTest {
    @Test
    fun failedDeliveryBeforeMaxAttemptsIsScheduledForRetryWithIncrementedAttemptsAndBackoff() {
        val item = QueueItem(id = "q1", messageId = "m1", recipient = "bob@test.local", attempts = 1)
        val store = RecordingQueueStore(items = listOf(item), deliveryFailure = IllegalStateException("smtp down"))
        val worker = MailQueueWorker(store, AppConfig(maxQueueAttempts = 3))
        val before = System.currentTimeMillis()

        worker.drain()

        assertEquals(listOf("q1"), store.inProgressIds)
        assertEquals("q1", store.retry?.id)
        assertEquals(2, store.retry?.attempts)
        assertEquals("smtp down", store.retry?.error)
        assertTrue(store.retry!!.nextAttemptAt >= before + 19_000L)
        assertTrue(store.retry!!.nextAttemptAt <= System.currentTimeMillis() + 25_000L)
        assertTrue(store.dead == null)
        assertTrue(store.deliveredIds.isEmpty())
    }

    @Test
    fun failedDeliveryAtMaxAttemptsIsDeadLetteredWithIncrementedAttempts() {
        val item = QueueItem(id = "q1", messageId = "m1", recipient = "bob@test.local", attempts = 2)
        val store = RecordingQueueStore(items = listOf(item), deliveryFailure = IllegalStateException("permanent"))
        val worker = MailQueueWorker(store, AppConfig(maxQueueAttempts = 3))

        worker.drain()

        assertEquals(DeadRecord("q1", "permanent", 3), store.dead)
        assertTrue(store.retry == null)
        assertTrue(store.deliveredIds.isEmpty())
    }

    @Test
    fun skippedConcurrentClaimIsNotDeliveredOrRetried() {
        val item = QueueItem(id = "q1", messageId = "m1", recipient = "bob@test.local", attempts = 0)
        val store = RecordingQueueStore(items = listOf(item), claimResult = false)
        val worker = MailQueueWorker(store, AppConfig(maxQueueAttempts = 3))

        worker.drain()

        assertEquals(listOf("q1"), store.claimAttempts)
        assertFalse(store.deliverCalled)
        assertTrue(store.deliveredIds.isEmpty())
        assertTrue(store.retry == null)
        assertTrue(store.dead == null)
    }

    @Test
    fun successfulDeliveryMarksQueueItemDeliveredOnlyAfterClaim() {
        val item = QueueItem(id = "q1", messageId = "m1", recipient = "bob@test.local", attempts = 0)
        val store = RecordingQueueStore(items = listOf(item))
        val worker = MailQueueWorker(store, AppConfig(maxQueueAttempts = 3))

        worker.drain()

        assertEquals(listOf("q1"), store.inProgressIds)
        assertTrue(store.deliverCalled)
        assertEquals(listOf("q1"), store.deliveredIds)
        assertTrue(store.retry == null)
        assertTrue(store.dead == null)
    }
}

private data class RetryRecord(val id: String, val error: String, val nextAttemptAt: Long, val attempts: Int)
private data class DeadRecord(val id: String, val error: String, val attempts: Int)

private class RecordingQueueStore(
    private val items: List<QueueItem>,
    private val claimResult: Boolean = true,
    private val deliveryFailure: RuntimeException? = null
) : MailStore {
    override val domain: String = "test.local"
    val claimAttempts = mutableListOf<String>()
    val inProgressIds = mutableListOf<String>()
    val deliveredIds = mutableListOf<String>()
    var retry: RetryRecord? = null
    var dead: DeadRecord? = null
    var deliverCalled = false

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
    override fun mailboxState(mailbox: String, messageId: String, includeDeleted: Boolean): MailboxState? = null
    override fun search(mailbox: String, query: String, box: String?, limit: Int): List<MailMessage> = emptyList()
    override fun setArchived(mailbox: String, messageId: String, archived: Boolean) {}
    override fun setDeleted(mailbox: String, messageId: String, deleted: Boolean) {}
    override fun markRead(mailbox: String, messageId: String, read: Boolean) {}
    override fun users(): List<User> = emptyList()
    override fun messages(limit: Int): List<MailMessage> = emptyList()
    override fun queueItems(status: QueueStatus?): List<QueueItem> = items
    override fun nextQueued(limit: Int): List<QueueItem> = items.take(limit)
    override fun markQueueInProgress(id: String, attempts: Int): Boolean {
        claimAttempts += id
        if (claimResult) inProgressIds += id
        return claimResult
    }
    override fun markQueueDelivered(id: String) { deliveredIds += id }
    override fun markQueueRetry(id: String, error: String, nextAttemptAt: Long, attempts: Int) {
        retry = RetryRecord(id, error, nextAttemptAt, attempts)
    }
    override fun markQueueDead(id: String, error: String, attempts: Int) {
        dead = DeadRecord(id, error, attempts)
    }
    override fun deliverQueued(item: QueueItem) {
        deliverCalled = true
        deliveryFailure?.let { throw it }
    }
}
