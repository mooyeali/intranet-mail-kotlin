package com.maoning.mail.store

interface MailStore {
    val domain: String
    fun normalizeMailbox(input: String): String
    fun userExists(mailbox: String): Boolean
    fun createUser(user: User): User
    fun findUser(mailbox: String): User?
    fun saveSession(session: Session): Session
    fun findSession(token: String): Session?
    fun revokeSession(token: String): Int = 0
    fun saveMessage(message: MailMessage): MailMessage
    fun queueRecipients(messageId: String, recipients: List<String>)
    fun inbox(mailbox: String, limit: Int = 100, offset: Int = 0): List<MailMessage>
    fun sent(mailbox: String, limit: Int = 100, offset: Int = 0): List<MailMessage>
    fun findSentMessage(messageId: String, mailbox: String): MailMessage? =
        sent(mailbox, limit = 500, offset = 0).firstOrNull { it.id == messageId }
    fun archive(mailbox: String, limit: Int = 100, offset: Int = 0): List<MailMessage>
    fun trash(mailbox: String, limit: Int = 100, offset: Int = 0): List<MailMessage>
    fun findMessageForMailbox(messageId: String, mailbox: String, includeDeleted: Boolean = false): MailMessage?
    fun mailboxState(mailbox: String, messageId: String, includeDeleted: Boolean = false): MailboxState?
    fun search(mailbox: String, query: String, box: String? = null, limit: Int = 100): List<MailMessage>
    fun setArchived(mailbox: String, messageId: String, archived: Boolean)
    fun setDeleted(mailbox: String, messageId: String, deleted: Boolean)
    fun markRead(mailbox: String, messageId: String, read: Boolean = true)
    fun users(): List<User>
    fun messages(limit: Int = 100): List<MailMessage>
    fun queueItems(status: QueueStatus? = null): List<QueueItem>
    fun queueItemsForMessage(messageId: String): List<QueueItem> = queueItems().filter { it.messageId == messageId }
    fun nextQueued(limit: Int = 20): List<QueueItem>
    fun markQueueInProgress(id: String, attempts: Int): Boolean
    fun markQueueDelivered(id: String)
    fun markQueueRetry(id: String, error: String, nextAttemptAt: Long, attempts: Int)
    fun markQueueDead(id: String, error: String, attempts: Int)
    fun deliverQueued(item: QueueItem)
    fun deleteExpiredSessions(now: Long): Int = 0
    fun close() {}
}
