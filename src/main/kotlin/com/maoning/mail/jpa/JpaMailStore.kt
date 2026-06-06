package com.maoning.mail.jpa

import com.maoning.mail.store.Attachment
import com.maoning.mail.store.MailMessage
import com.maoning.mail.store.MailStore
import com.maoning.mail.store.MailboxState
import com.maoning.mail.store.QueueItem
import com.maoning.mail.store.QueueStatus
import com.maoning.mail.store.Session
import com.maoning.mail.store.User
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

@Service
class JpaMailStore(
    private val properties: MailProperties,
    private val users: UserRepository,
    private val sessions: SessionRepository,
    private val messages: MessageRepository,
    private val mailboxes: MailboxRepository,
    private val queue: QueueRepository
) : MailStore {
    override val domain: String get() = properties.domain
    private val json = Json { ignoreUnknownKeys = true }

    override fun normalizeMailbox(input: String): String {
        val trimmed = input.trim().lowercase()
        return if (trimmed.contains('@')) trimmed else "$trimmed@$domain"
    }

    override fun userExists(mailbox: String): Boolean = findUser(mailbox) != null

    @Transactional
    override fun createUser(user: User): User {
        val mailbox = normalizeMailbox(user.mailbox)
        require(mailbox.endsWith("@$domain")) { "Only local domain @$domain is allowed" }
        val normalized = user.copy(mailbox = mailbox, username = user.username.trim().lowercase())
        users.save(normalized.toEntity())
        return normalized
    }

    override fun findUser(mailbox: String): User? =
        users.findByMailboxOrUsername(normalizeMailbox(mailbox), mailbox.trim().lowercase())?.toDomain()

    @Transactional
    override fun saveSession(session: Session): Session {
        sessions.save(session.toEntity(tokenHash(session.token)))
        return session
    }

    @Transactional
    override fun findSession(token: String): Session? {
        val tokenHash = tokenHash(token)
        val session = sessions.findById(tokenHash).orElse(null)?.toDomain(token) ?: return null
        if (session.expiresAt < Instant.now().toEpochMilli()) {
            sessions.deleteById(tokenHash)
            return null
        }
        return session
    }

    @Transactional
    override fun revokeSession(token: String): Int = sessions.revokeByTokenHash(tokenHash(token))

    @Transactional
    override fun saveMessage(message: MailMessage): MailMessage {
        val normalized = message.copy(
            from = normalizeMailbox(message.from),
            to = message.to.map { normalizeMailbox(it) }
        )
        messages.save(normalized.toEntity(json))
        mailboxes.save(MailboxEntity(UUID.randomUUID().toString(), normalized.from, normalized.id, "sent", normalized.createdAt))
        return normalized
    }

    @Transactional
    override fun queueRecipients(messageId: String, recipients: List<String>) {
        val now = Instant.now().toEpochMilli()
        queue.saveAll(recipients.map { recipient ->
            QueueEntity(
                id = UUID.randomUUID().toString(),
                messageId = messageId,
                recipient = normalizeMailbox(recipient),
                status = QueueStatus.QUEUED.name,
                attempts = 0,
                nextAttemptAt = now,
                createdAt = now,
                updatedAt = now
            )
        })
    }

    @Transactional
    override fun deliverQueued(item: QueueItem) {
        val message = messages.findById(item.messageId).orElse(null) ?: error("Message not found: ${item.messageId}")
        require(userExists(item.recipient)) { "Recipient not found: ${item.recipient}" }
        mailboxes.save(MailboxEntity(UUID.randomUUID().toString(), normalizeMailbox(item.recipient), message.id, "inbox", Instant.now().toEpochMilli()))
    }

    override fun inbox(mailbox: String, limit: Int, offset: Int): List<MailMessage> = mailboxMessages(normalizeMailbox(mailbox), "inbox", archived = false, deleted = false, limit, offset)
    override fun sent(mailbox: String, limit: Int, offset: Int): List<MailMessage> = mailboxMessages(normalizeMailbox(mailbox), "sent", archived = false, deleted = false, limit, offset)
    override fun archive(mailbox: String, limit: Int, offset: Int): List<MailMessage> = mailboxMessages(normalizeMailbox(mailbox), null, archived = true, deleted = false, limit, offset)
    override fun trash(mailbox: String, limit: Int, offset: Int): List<MailMessage> = mailboxMessages(normalizeMailbox(mailbox), null, archived = null, deleted = true, limit, offset)

    override fun findMessageForMailbox(messageId: String, mailbox: String, includeDeleted: Boolean): MailMessage? {
        val row = mailboxRow(mailbox, messageId, includeDeleted)
        return row?.let { messages.findById(messageId).orElse(null)?.toDomain(json, it.readFlag) }
    }

    override fun findSentMessage(messageId: String, mailbox: String): MailMessage? =
        mailboxes.findFirstByMessageIdAndMailboxAndBoxAndDeletedFalse(messageId, normalizeMailbox(mailbox), "sent")
            ?.let { messages.findById(messageId).orElse(null)?.toDomain(json, it.readFlag) }

    override fun mailboxState(mailbox: String, messageId: String, includeDeleted: Boolean): MailboxState? =
        mailboxRow(mailbox, messageId, includeDeleted)?.toState()

    override fun search(mailbox: String, query: String, box: String?, limit: Int): List<MailMessage> {
        val normalized = normalizeMailbox(mailbox)
        val q = query.trim().lowercase()
        val allowedBox = box?.takeIf { it == "inbox" || it == "sent" }
        val cappedLimit = limit.coerceIn(1, 500)
        return mailboxes.searchMailboxMessageRows(normalized, q, "%${q.escapeLike()}%", allowedBox, PageRequest.of(0, cappedLimit))
            .map { row -> row.toDomain(json) }
    }

    @Transactional
    override fun setArchived(mailbox: String, messageId: String, archived: Boolean) { mailboxes.updateArchived(normalizeMailbox(mailbox), messageId, archived) }
    @Transactional
    override fun setDeleted(mailbox: String, messageId: String, deleted: Boolean) { mailboxes.updateDeleted(normalizeMailbox(mailbox), messageId, deleted) }
    @Transactional
    override fun markRead(mailbox: String, messageId: String, read: Boolean) { mailboxes.updateRead(normalizeMailbox(mailbox), messageId, read) }

    override fun users(): List<User> = users.findAllByOrderByCreatedAtDesc().map { it.toDomain() }
    override fun messages(limit: Int): List<MailMessage> = messages.findAllByOrderByCreatedAtDesc(PageRequest.of(0, limit)).map { it.toDomain(json) }
    override fun queueItems(status: QueueStatus?): List<QueueItem> = (status?.let { queue.findByStatusOrderByUpdatedAtDesc(it.name) } ?: queue.findAllByOrderByUpdatedAtDesc()).map { it.toDomain() }
    override fun queueItemsForMessage(messageId: String): List<QueueItem> = queue.findByMessageIdOrderByUpdatedAtDesc(messageId).map { it.toDomain() }
    override fun nextQueued(limit: Int): List<QueueItem> = queue.nextQueued(listOf(QueueStatus.QUEUED.name, QueueStatus.RETRY.name), Instant.now().toEpochMilli(), PageRequest.of(0, limit)).map { it.toDomain() }

    @Transactional
    override fun markQueueInProgress(id: String, attempts: Int): Boolean {
        val now = Instant.now().toEpochMilli()
        return queue.claimReady(id, attempts, attempts + 1, now, listOf(QueueStatus.QUEUED.name, QueueStatus.RETRY.name)) > 0
    }

    @Transactional
    override fun markQueueDelivered(id: String) = updateQueue(id, QueueStatus.DELIVERED, null, null, false)
    @Transactional
    override fun markQueueRetry(id: String, error: String, nextAttemptAt: Long, attempts: Int) = updateQueue(id, QueueStatus.RETRY, error, nextAttemptAt, false)
    @Transactional
    override fun markQueueDead(id: String, error: String, attempts: Int) = updateQueue(id, QueueStatus.DEAD, error, null, false)

    private fun mailboxMessages(mailbox: String, box: String?, archived: Boolean?, deleted: Boolean, limit: Int, offset: Int): List<MailMessage> =
        mailboxes.findMailboxMessageRows(mailbox, box, archived, deleted, PageRequest.of(offset / limit.coerceAtLeast(1), limit.coerceIn(1, 200)))
            .map { row -> row.toDomain(json) }

    @Transactional
    override fun deleteExpiredSessions(now: Long): Int = sessions.deleteExpired(now)

    private fun mailboxRow(mailbox: String, messageId: String, includeDeleted: Boolean): MailboxEntity? {
        val normalized = normalizeMailbox(mailbox)
        return if (includeDeleted) mailboxes.findFirstByMessageIdAndMailbox(messageId, normalized)
            else mailboxes.findFirstByMessageIdAndMailboxAndDeletedFalse(messageId, normalized)
    }

    private fun updateQueue(id: String, status: QueueStatus, error: String?, next: Long?, bumpAttempts: Boolean) {
        val entity = queue.findById(id).orElse(null) ?: return
        entity.status = status.name
        entity.lastError = error
        if (next != null) entity.nextAttemptAt = next
        if (bumpAttempts) entity.attempts += 1
        entity.updatedAt = Instant.now().toEpochMilli()
        queue.save(entity)
    }
}

private fun User.toEntity() = UserEntity(id, username, mailbox, passwordHash, createdAt)
private fun UserEntity.toDomain() = User(id, username, mailbox, passwordHash, createdAt)
private fun Session.toEntity(tokenHash: String) = SessionEntity(tokenHash, mailbox, createdAt, expiresAt)
private fun SessionEntity.toDomain(rawToken: String) = Session(rawToken, mailbox, createdAt, expiresAt)
private fun MailboxEntity.toState() = MailboxState(messageId, mailbox, box, readFlag, archived, deleted)
private fun MailMessage.toEntity(json: Json) = MessageEntity(id, from, json.encodeToString(to), subject, body, raw, json.encodeToString(attachments), read, createdAt)
private fun MessageEntity.toDomain(json: Json, mailboxRead: Boolean? = null) = MailMessage(id, sender, json.decodeFromString<List<String>>(recipients), subject, body, raw, json.decodeFromString<List<Attachment>>(attachments), mailboxRead ?: readFlag, createdAt)
fun MailboxMessageRow.toDomain(json: Json) = message.toDomain(json, mailboxRead)
private fun String.escapeLike(): String = replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
private fun QueueEntity.toDomain() = QueueItem(id, messageId, recipient, QueueStatus.valueOf(status), attempts, lastError, nextAttemptAt, createdAt, updatedAt)
private fun tokenHash(token: String): String = MessageDigest.getInstance("SHA-256")
    .digest(token.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
