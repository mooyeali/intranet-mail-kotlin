package com.maoning.mail.api

import com.maoning.mail.attachment.AttachmentStorage
import com.maoning.mail.audit.AuditService
import com.maoning.mail.auth.AuthService
import com.maoning.mail.config.AppConfig
import com.maoning.mail.mail.MailService
import com.maoning.mail.mime.MimeParser
import com.maoning.mail.queue.MailQueueWorker
import com.maoning.mail.store.MailMessage
import com.maoning.mail.store.MailStore
import com.maoning.mail.store.MailboxState
import com.maoning.mail.store.QueueItem
import com.maoning.mail.store.QueueStatus
import com.maoning.mail.store.Session
import com.maoning.mail.store.User
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.HttpStatus
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MailboxActionsControllerTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun newMailboxRoutesRequireBearerAuth() {
        val controller = controller(store = RecordingMailboxStore())

        val responses = listOf(
            controller.messageDetail(null, "m1"),
            controller.deliveryStatus(null, "m1"),
            controller.archive(null, 100, 0),
            controller.trash(null, 100, 0),
            controller.markRead(null, "m1"),
            controller.markUnread(null, "m1"),
            controller.archiveMessage(null, "m1"),
            controller.unarchiveMessage(null, "m1"),
            controller.trashMessage(null, "m1"),
            controller.restoreMessage(null, "m1")
        )

        responses.forEach { response ->
            assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
            assertEquals(ErrorResponse("missing or invalid token"), response.body)
        }
    }

    @Test
    fun messageDetailIsMailboxAuthorizedAndDoesNotMarkRead() {
        val message = MailMessage(id = "m1", from = "alice@test.local", to = listOf("bob@test.local"), subject = "Hello", body = "Body", read = false)
        val store = RecordingMailboxStore(messagesByMailbox = mutableMapOf("bob@test.local:m1:false" to message))
        val controller = controller(store = store)

        val response = controller.messageDetail("Bearer good-token", "m1")

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(message, response.body)
        assertEquals(listOf("bob@test.local:m1:false"), store.findMessageCalls)
        assertTrue(store.markReadCalls.isEmpty())
    }

    @Test
    fun archiveAndTrashListsReturnOnlyAuthenticatedMailboxRows() {
        val archived = MailMessage(id = "archived", from = "alice@test.local", to = listOf("bob@test.local"), subject = "A", body = "B")
        val trashed = MailMessage(id = "trashed", from = "alice@test.local", to = listOf("bob@test.local"), subject = "T", body = "B")
        val store = RecordingMailboxStore(archiveMessages = listOf(archived), trashMessages = listOf(trashed))
        val controller = controller(store = store)

        assertEquals(listOf(archived), controller.archive("Bearer good-token", 100, 0).body)
        assertEquals(listOf(trashed), controller.trash("Bearer good-token", 100, 0).body)
        assertEquals(listOf("bob@test.local:100:0"), store.archiveCalls)
        assertEquals(listOf("bob@test.local:100:0"), store.trashCalls)
    }

    @Test
    fun mailboxListRoutesApplyBoundedPaginationBeforeCallingStore() {
        val store = RecordingMailboxStore()
        val controller = controller(store = store)

        controller.inbox("Bearer good-token", limit = 999, offset = -10)
        controller.sent("Bearer good-token", limit = 0, offset = 20)
        controller.archive("Bearer good-token", limit = 25, offset = 5)
        controller.trash("Bearer good-token", limit = 501, offset = 2)

        assertEquals(listOf("bob@test.local:200:0"), store.inboxCalls)
        assertEquals(listOf("bob@test.local:1:20"), store.sentCalls)
        assertEquals(listOf("bob@test.local:25:5"), store.archiveCalls)
        assertEquals(listOf("bob@test.local:200:2"), store.trashCalls)
    }


    @Test
    fun readUnreadArchiveUnarchiveTrashAndRestoreReturnUpdatedMailboxState() {
        val store = RecordingMailboxStore(
            states = mutableMapOf(
                "bob@test.local:m1:false" to MailboxState("m1", "bob@test.local", "inbox", read = false, archived = false, deleted = false),
                "bob@test.local:m1:true" to MailboxState("m1", "bob@test.local", "inbox", read = false, archived = true, deleted = true)
            )
        )
        val controller = controller(store = store)

        assertEquals(MailboxStateResponse("m1", "bob@test.local", "inbox", read = true, archived = false, deleted = false), controller.markRead("Bearer good-token", "m1").body)
        assertEquals(MailboxStateResponse("m1", "bob@test.local", "inbox", read = false, archived = false, deleted = false), controller.markUnread("Bearer good-token", "m1").body)
        assertEquals(MailboxStateResponse("m1", "bob@test.local", "inbox", read = false, archived = true, deleted = false), controller.archiveMessage("Bearer good-token", "m1").body)
        assertEquals(MailboxStateResponse("m1", "bob@test.local", "inbox", read = false, archived = false, deleted = false), controller.unarchiveMessage("Bearer good-token", "m1").body)
        assertEquals(MailboxStateResponse("m1", "bob@test.local", "inbox", read = false, archived = false, deleted = true), controller.trashMessage("Bearer good-token", "m1").body)
        assertEquals(MailboxStateResponse("m1", "bob@test.local", "inbox", read = false, archived = false, deleted = false), controller.restoreMessage("Bearer good-token", "m1").body)

        assertEquals(listOf("bob@test.local:m1:true", "bob@test.local:m1:false"), store.markReadCalls)
        assertEquals(listOf("bob@test.local:m1:true", "bob@test.local:m1:false"), store.setArchivedCalls)
        assertEquals(listOf("bob@test.local:m1:true", "bob@test.local:m1:false"), store.setDeletedCalls)
    }

    @Test
    fun nonRestoreActionsReturnNotFoundForMissingOrTrashedRowsWithoutMutating() {
        val store = RecordingMailboxStore(states = mutableMapOf("bob@test.local:m1:true" to MailboxState("m1", "bob@test.local", "inbox", read = false, archived = false, deleted = true)))
        val controller = controller(store = store)

        val response = controller.archiveMessage("Bearer good-token", "m1")

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals(ErrorResponse("message not found"), response.body)
        assertFalse(store.setArchivedCalls.any { it == "bob@test.local:m1:true" })
    }

    @Test
    fun restoreMayActOnDeletedRowsAndCrossMailboxRowsRemainHidden() {
        val store = RecordingMailboxStore(states = mutableMapOf("bob@test.local:m1:true" to MailboxState("m1", "bob@test.local", "inbox", read = false, archived = false, deleted = true)))
        val controller = controller(store = store)

        val restored = controller.restoreMessage("Bearer good-token", "m1")
        val crossMailbox = controller.restoreMessage("Bearer other-token", "m1")

        assertEquals(HttpStatus.OK, restored.statusCode)
        assertEquals(MailboxStateResponse("m1", "bob@test.local", "inbox", read = false, archived = false, deleted = false), restored.body)
        assertEquals(HttpStatus.NOT_FOUND, crossMailbox.statusCode)
        assertEquals(ErrorResponse("message not found"), crossMailbox.body)
    }


    @Test
    fun deliveryStatusIsVisibleOnlyToSenderAndReturnsPerRecipientQueueStatus() {
        val sent = MailMessage(id = "m1", from = "bob@test.local", to = listOf("alice@test.local"), subject = "S", body = "B")
        val store = RecordingMailboxStore(
            sentMessages = listOf(sent),
            queueItemsByMessage = mapOf("m1" to listOf(QueueItem(id = "q1", messageId = "m1", recipient = "alice@test.local", status = QueueStatus.RETRY, attempts = 2, lastError = "mailbox locked", nextAttemptAt = 1234L, updatedAt = 1200L)))
        )
        val controller = controller(store = store)

        val response = controller.deliveryStatus("Bearer good-token", "m1")
        val crossMailbox = controller.deliveryStatus("Bearer other-token", "m1")

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(
            DeliveryStatusResponse("m1", listOf(DeliveryRecipientStatusResponse("alice@test.local", QueueStatus.RETRY, 2, "mailbox locked", 1234L, 1200L))),
            response.body
        )
        assertEquals(HttpStatus.NOT_FOUND, crossMailbox.statusCode)
        assertEquals(listOf("bob@test.local:m1", "alice@test.local:m1"), store.findSentCalls)
        assertEquals(listOf("m1"), store.queueStatusCalls)
    }

    private fun controller(store: RecordingMailboxStore): MailController {
        val config = AppConfig(domain = store.domain, attachmentDir = tempDir.toString())
        val authService = AuthService(store)
        val auditService = AuditService()
        val attachmentStorage = AttachmentStorage(config.attachmentDir)
        return MailController(
            config,
            authService,
            MailService(store, MimeParser(attachmentStorage), auditService),
            store,
            MailQueueWorker(store, config),
            auditService,
            attachmentStorage,
            com.maoning.mail.admin.AdminAuthService(config)
        )
    }
}

private class RecordingMailboxStore(
    override val domain: String = "test.local",
    private val messagesByMailbox: MutableMap<String, MailMessage> = mutableMapOf(),
    private val states: MutableMap<String, MailboxState> = mutableMapOf(),
    private val archiveMessages: List<MailMessage> = emptyList(),
    private val trashMessages: List<MailMessage> = emptyList(),
    private val sentMessages: List<MailMessage> = emptyList(),
    private val queueItemsByMessage: Map<String, List<QueueItem>> = emptyMap()
) : MailStore {
    val findMessageCalls = mutableListOf<String>()
    val findSentCalls = mutableListOf<String>()
    val inboxCalls = mutableListOf<String>()
    val sentCalls = mutableListOf<String>()
    val archiveCalls = mutableListOf<String>()
    val trashCalls = mutableListOf<String>()
    val markReadCalls = mutableListOf<String>()
    val setArchivedCalls = mutableListOf<String>()
    val setDeletedCalls = mutableListOf<String>()
    val queueStatusCalls = mutableListOf<String>()

    override fun normalizeMailbox(input: String): String = if ('@' in input) input.trim().lowercase() else "${input.trim().lowercase()}@$domain"
    override fun userExists(mailbox: String): Boolean = true
    override fun createUser(user: User): User = user
    override fun findUser(mailbox: String): User? = null
    override fun saveSession(session: Session): Session = session
    override fun findSession(token: String): Session? = when (token) {
        "good-token" -> Session(token, "bob@test.local")
        "other-token" -> Session(token, "alice@test.local")
        else -> null
    }
    override fun saveMessage(message: MailMessage): MailMessage = message
    override fun queueRecipients(messageId: String, recipients: List<String>) {}
    override fun inbox(mailbox: String, limit: Int, offset: Int): List<MailMessage> {
        inboxCalls += "${normalizeMailbox(mailbox)}:$limit:$offset"
        return emptyList()
    }
    override fun sent(mailbox: String, limit: Int, offset: Int): List<MailMessage> {
        sentCalls += "${normalizeMailbox(mailbox)}:$limit:$offset"
        return sentMessages.filter { normalizeMailbox(it.from) == normalizeMailbox(mailbox) }
    }
    override fun findSentMessage(messageId: String, mailbox: String): MailMessage? {
        findSentCalls += "${normalizeMailbox(mailbox)}:$messageId"
        return sentMessages.firstOrNull { it.id == messageId && normalizeMailbox(it.from) == normalizeMailbox(mailbox) }
    }
    override fun archive(mailbox: String, limit: Int, offset: Int): List<MailMessage> {
        archiveCalls += "${normalizeMailbox(mailbox)}:$limit:$offset"
        return archiveMessages
    }
    override fun trash(mailbox: String, limit: Int, offset: Int): List<MailMessage> {
        trashCalls += "${normalizeMailbox(mailbox)}:$limit:$offset"
        return trashMessages
    }
    override fun findMessageForMailbox(messageId: String, mailbox: String, includeDeleted: Boolean): MailMessage? {
        val key = "${normalizeMailbox(mailbox)}:$messageId:$includeDeleted"
        findMessageCalls += key
        return messagesByMailbox[key]
    }
    override fun mailboxState(mailbox: String, messageId: String, includeDeleted: Boolean): MailboxState? {
        val normalized = normalizeMailbox(mailbox)
        val active = states["$normalized:$messageId:false"]
        return if (includeDeleted) active ?: states["$normalized:$messageId:true"] else active
    }
    override fun search(mailbox: String, query: String, box: String?, limit: Int): List<MailMessage> = emptyList()
    override fun setArchived(mailbox: String, messageId: String, archived: Boolean) {
        setArchivedCalls += "${normalizeMailbox(mailbox)}:$messageId:$archived"
        updateState(mailbox, messageId, includeDeleted = false) { copy(archived = archived, deleted = false) }
    }
    override fun setDeleted(mailbox: String, messageId: String, deleted: Boolean) {
        setDeletedCalls += "${normalizeMailbox(mailbox)}:$messageId:$deleted"
        updateState(mailbox, messageId, includeDeleted = true) { copy(deleted = deleted) }
        updateState(mailbox, messageId, includeDeleted = false) { copy(deleted = deleted) }
    }
    override fun markRead(mailbox: String, messageId: String, read: Boolean) {
        markReadCalls += "${normalizeMailbox(mailbox)}:$messageId:$read"
        updateState(mailbox, messageId, includeDeleted = false) { copy(read = read) }
    }
    private fun updateState(mailbox: String, messageId: String, includeDeleted: Boolean, transform: MailboxState.() -> MailboxState) {
        val normalized = normalizeMailbox(mailbox)
        val key = "$normalized:$messageId:$includeDeleted"
        val fallbackKey = "$normalized:$messageId:${!includeDeleted}"
        val current = states[key] ?: states[fallbackKey] ?: return
        states.remove(fallbackKey)
        states[key] = current.transform()
    }
    override fun users(): List<User> = emptyList()
    override fun messages(limit: Int): List<MailMessage> = emptyList()
    override fun queueItems(status: QueueStatus?): List<QueueItem> = queueItemsByMessage.values.flatten().filter { status == null || it.status == status }
    override fun queueItemsForMessage(messageId: String): List<QueueItem> {
        queueStatusCalls += messageId
        return queueItemsByMessage[messageId] ?: emptyList()
    }
    override fun nextQueued(limit: Int): List<QueueItem> = emptyList()
    override fun markQueueInProgress(id: String, attempts: Int): Boolean = false
    override fun markQueueDelivered(id: String) {}
    override fun markQueueRetry(id: String, error: String, nextAttemptAt: Long, attempts: Int) {}
    override fun markQueueDead(id: String, error: String, attempts: Int) {}
    override fun deliverQueued(item: QueueItem) {}
}
