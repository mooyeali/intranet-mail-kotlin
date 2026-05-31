package com.maoning.mail.db

import com.maoning.mail.store.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.sql.Connection
import org.flywaydb.core.Flyway
import javax.sql.DataSource
import java.time.Instant
import java.util.UUID

class H2MailStore(
    override val domain: String,
    private val dataSource: DataSource
) : MailStore {
    private val json = Json { ignoreUnknownKeys = true }

    init {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .load()
            .migrate()
    }

    private fun conn(): Connection = dataSource.connection

    override fun normalizeMailbox(input: String): String {
        val trimmed = input.trim().lowercase()
        return if (trimmed.contains('@')) trimmed else "$trimmed@$domain"
    }

    override fun userExists(mailbox: String): Boolean = findUser(mailbox) != null

    override fun createUser(user: User): User {
        val mailbox = normalizeMailbox(user.mailbox)
        require(mailbox.endsWith("@$domain")) { "Only local domain @$domain is allowed" }
        val normalized = user.copy(mailbox = mailbox, username = user.username.trim().lowercase())
        conn().use { c ->
            c.prepareStatement("insert into users(id, username, mailbox, password_hash, created_at) values(?,?,?,?,?)").use { ps ->
                ps.setString(1, normalized.id)
                ps.setString(2, normalized.username)
                ps.setString(3, normalized.mailbox)
                ps.setString(4, normalized.passwordHash)
                ps.setLong(5, normalized.createdAt)
                ps.executeUpdate()
            }
        }
        return normalized
    }

    override fun findUser(mailbox: String): User? = conn().use { c ->
        c.prepareStatement("select * from users where mailbox=? or username=?").use { ps ->
            val normalized = normalizeMailbox(mailbox)
            ps.setString(1, normalized)
            ps.setString(2, mailbox.trim().lowercase())
            ps.executeQuery().use { rs -> if (rs.next()) User(rs.getString("id"), rs.getString("username"), rs.getString("mailbox"), rs.getString("password_hash"), rs.getLong("created_at")) else null }
        }
    }

    override fun saveSession(session: Session): Session {
        conn().use { c ->
            c.prepareStatement("merge into sessions(token, mailbox, created_at, expires_at) values(?,?,?,?)").use { ps ->
                ps.setString(1, session.token)
                ps.setString(2, session.mailbox)
                ps.setLong(3, session.createdAt)
                ps.setLong(4, session.expiresAt)
                ps.executeUpdate()
            }
        }
        return session
    }

    override fun findSession(token: String): Session? {
        conn().use { c ->
            c.prepareStatement("select * from sessions where token=?").use { ps ->
                ps.setString(1, token)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    val session = Session(rs.getString("token"), rs.getString("mailbox"), rs.getLong("created_at"), rs.getLong("expires_at"))
                    if (session.expiresAt < Instant.now().toEpochMilli()) {
                        c.prepareStatement("delete from sessions where token=?").use { del ->
                            del.setString(1, token)
                            del.executeUpdate()
                        }
                        return null
                    }
                    return session
                }
            }
        }
    }

    override fun saveMessage(message: MailMessage): MailMessage {
        conn().use { c ->
            c.autoCommit = false
            try {
                c.prepareStatement("insert into messages(id, sender, recipients, subject, body, raw, attachments, read_flag, created_at) values(?,?,?,?,?,?,?,?,?)").use { ps ->
                    ps.setString(1, message.id)
                    ps.setString(2, normalizeMailbox(message.from))
                    ps.setString(3, json.encodeToString(message.to.map { normalizeMailbox(it) }))
                    ps.setString(4, message.subject)
                    ps.setString(5, message.body)
                    ps.setString(6, message.raw)
                    ps.setString(7, json.encodeToString(message.attachments))
                    ps.setBoolean(8, message.read)
                    ps.setLong(9, message.createdAt)
                    ps.executeUpdate()
                }
                addMailbox(c, normalizeMailbox(message.from), message.id, "sent", message.createdAt)
                c.commit()
            } catch (ex: Exception) {
                c.rollback()
                throw ex
            }
        }
        return message
    }

    override fun queueRecipients(messageId: String, recipients: List<String>) {
        val now = Instant.now().toEpochMilli()
        conn().use { c ->
            recipients.map { normalizeMailbox(it) }.forEach { recipient ->
                c.prepareStatement("insert into queue(id, message_id, recipient, status, attempts, last_error, next_attempt_at, created_at, updated_at) values(?,?,?,?,?,?,?,?,?)").use { ps ->
                    ps.setString(1, UUID.randomUUID().toString())
                    ps.setString(2, messageId)
                    ps.setString(3, recipient)
                    ps.setString(4, QueueStatus.QUEUED.name)
                    ps.setInt(5, 0)
                    ps.setString(6, null)
                    ps.setLong(7, now)
                    ps.setLong(8, now)
                    ps.setLong(9, now)
                    ps.executeUpdate()
                }
            }
        }
    }

    fun deliverQueued(item: QueueItem) {
        val message = messageById(item.messageId) ?: error("Message not found: ${item.messageId}")
        require(userExists(item.recipient)) { "Recipient not found: ${item.recipient}" }
        conn().use { c -> addMailbox(c, item.recipient, message.id, "inbox", Instant.now().toEpochMilli()) }
    }

    private fun addMailbox(c: Connection, mailbox: String, messageId: String, box: String, createdAt: Long) {
        c.prepareStatement("insert into mailboxes(id, mailbox, message_id, box, created_at) values(?,?,?,?,?)").use { ps ->
            ps.setString(1, UUID.randomUUID().toString())
            ps.setString(2, mailbox)
            ps.setString(3, messageId)
            ps.setString(4, box)
            ps.setLong(5, createdAt)
            ps.executeUpdate()
        }
    }

    override fun inbox(mailbox: String): List<MailMessage> = mailboxMessages(normalizeMailbox(mailbox), "inbox", archived = false, deleted = false)
    override fun sent(mailbox: String): List<MailMessage> = mailboxMessages(normalizeMailbox(mailbox), "sent", archived = false, deleted = false)
    override fun archive(mailbox: String): List<MailMessage> = mailboxMessages(normalizeMailbox(mailbox), null, archived = true, deleted = false)
    override fun trash(mailbox: String): List<MailMessage> = mailboxMessages(normalizeMailbox(mailbox), null, archived = null, deleted = true)

    override fun findMessageForMailbox(messageId: String, mailbox: String, includeDeleted: Boolean): MailMessage? = conn().use { c ->
        val sql = buildString {
            append("select m.*, b.read_flag as mailbox_read_flag from messages m join mailboxes b on b.message_id=m.id where m.id=? and b.mailbox=? ")
            if (!includeDeleted) append("and b.deleted=false ")
            append("limit 1")
        }
        c.prepareStatement(sql).use { ps ->
            ps.setString(1, messageId)
            ps.setString(2, normalizeMailbox(mailbox))
            ps.executeQuery().use { rs -> if (rs.next()) rs.toMessage() else null }
        }
    }

    override fun search(mailbox: String, query: String, box: String?, limit: Int): List<MailMessage> = conn().use { c ->
        val like = "%${query.lowercase()}%"
        val sql = buildString {
            append("select distinct m.*, b.read_flag as mailbox_read_flag from messages m join mailboxes b on b.message_id=m.id where b.mailbox=? and b.deleted=false ")
            if (box != null) append("and b.box=? ")
            append("and (lower(m.subject) like ? or lower(m.body) like ? or lower(m.sender) like ? or lower(m.recipients) like ?) order by m.created_at desc limit ?")
        }
        c.prepareStatement(sql).use { ps ->
            var i = 1
            ps.setString(i++, normalizeMailbox(mailbox))
            if (box != null) ps.setString(i++, box)
            repeat(4) { ps.setString(i++, like) }
            ps.setInt(i, limit)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toMessage()) } }
        }
    }

    override fun setArchived(mailbox: String, messageId: String, archived: Boolean) = updateMailboxFlag(mailbox, messageId, "archived", archived)
    override fun setDeleted(mailbox: String, messageId: String, deleted: Boolean) = updateMailboxFlag(mailbox, messageId, "deleted", deleted)
    override fun markRead(mailbox: String, messageId: String, read: Boolean) = updateMailboxFlag(mailbox, messageId, "read_flag", read)

    private fun updateMailboxFlag(mailbox: String, messageId: String, column: String, value: Boolean) {
        require(column in setOf("archived", "deleted", "read_flag")) { "Invalid mailbox flag" }
        conn().use { c ->
            c.prepareStatement("update mailboxes set $column=? where mailbox=? and message_id=?").use { ps ->
                ps.setBoolean(1, value)
                ps.setString(2, normalizeMailbox(mailbox))
                ps.setString(3, messageId)
                ps.executeUpdate()
            }
        }
    }

    private fun mailboxMessages(mailbox: String, box: String?, archived: Boolean?, deleted: Boolean): List<MailMessage> = conn().use { c ->
        val sql = buildString {
            append("select m.*, b.read_flag as mailbox_read_flag from messages m join mailboxes b on b.message_id=m.id where b.mailbox=? and b.deleted=? ")
            if (box != null) append("and b.box=? ")
            if (archived != null) append("and b.archived=? ")
            append("order by b.created_at desc")
        }
        c.prepareStatement(sql).use { ps ->
            var i = 1
            ps.setString(i++, mailbox)
            ps.setBoolean(i++, deleted)
            if (box != null) ps.setString(i++, box)
            if (archived != null) ps.setBoolean(i, archived)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toMessage()) } }
        }
    }

    override fun users(): List<User> = conn().use { c ->
        c.prepareStatement("select * from users order by created_at desc").use { ps -> ps.executeQuery().use { rs -> buildList { while (rs.next()) add(User(rs.getString("id"), rs.getString("username"), rs.getString("mailbox"), rs.getString("password_hash"), rs.getLong("created_at"))) } } }
    }

    override fun messages(limit: Int): List<MailMessage> = conn().use { c ->
        c.prepareStatement("select * from messages order by created_at desc limit ?").use { ps -> ps.setInt(1, limit); ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toMessage()) } } }
    }

    private fun messageById(id: String): MailMessage? = conn().use { c ->
        c.prepareStatement("select * from messages where id=?").use { ps -> ps.setString(1, id); ps.executeQuery().use { rs -> if (rs.next()) rs.toMessage() else null } }
    }

    override fun queueItems(status: QueueStatus?): List<QueueItem> = conn().use { c ->
        val sql = if (status == null) "select * from queue order by updated_at desc" else "select * from queue where status=? order by updated_at desc"
        c.prepareStatement(sql).use { ps -> if (status != null) ps.setString(1, status.name); ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toQueueItem()) } } }
    }

    override fun nextQueued(limit: Int): List<QueueItem> = conn().use { c ->
        c.prepareStatement("select * from queue where status in (?, ?) and next_attempt_at <= ? order by next_attempt_at asc limit ?").use { ps ->
            ps.setString(1, QueueStatus.QUEUED.name)
            ps.setString(2, QueueStatus.RETRY.name)
            ps.setLong(3, Instant.now().toEpochMilli())
            ps.setInt(4, limit)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toQueueItem()) } }
        }
    }

    override fun markQueueDelivered(id: String) = updateQueue(id, QueueStatus.DELIVERED, null, null, false)
    override fun markQueueRetry(id: String, error: String, nextAttemptAt: Long) = updateQueue(id, QueueStatus.RETRY, error, nextAttemptAt, true)
    override fun markQueueDead(id: String, error: String) = updateQueue(id, QueueStatus.DEAD, error, null, true)

    private fun updateQueue(id: String, status: QueueStatus, error: String?, next: Long?, bumpAttempts: Boolean) {
        conn().use { c ->
            val sql = "update queue set status=?, last_error=?, next_attempt_at=coalesce(?, next_attempt_at), attempts=attempts+?, updated_at=? where id=?"
            c.prepareStatement(sql).use { ps ->
                ps.setString(1, status.name)
                ps.setString(2, error)
                if (next == null) ps.setObject(3, null) else ps.setLong(3, next)
                ps.setInt(4, if (bumpAttempts) 1 else 0)
                ps.setLong(5, Instant.now().toEpochMilli())
                ps.setString(6, id)
                ps.executeUpdate()
            }
        }
    }

    private fun java.sql.ResultSet.toMessage() = MailMessage(
        id = getString("id"),
        from = getString("sender"),
        to = json.decodeFromString(getString("recipients")),
        subject = getString("subject"),
        body = getString("body"),
        raw = getString("raw"),
        attachments = json.decodeFromString(getString("attachments")),
        read = runCatching { getBoolean("mailbox_read_flag") }.getOrElse { getBoolean("read_flag") },
        createdAt = getLong("created_at")
    )

    private fun java.sql.ResultSet.toQueueItem() = QueueItem(
        id = getString("id"),
        messageId = getString("message_id"),
        recipient = getString("recipient"),
        status = QueueStatus.valueOf(getString("status")),
        attempts = getInt("attempts"),
        lastError = getString("last_error"),
        nextAttemptAt = getLong("next_attempt_at"),
        createdAt = getLong("created_at"),
        updatedAt = getLong("updated_at")
    )
}
