package com.maoning.mail.store

interface MailStore {
    val domain: String
    fun normalizeMailbox(input: String): String
    fun userExists(mailbox: String): Boolean
    fun createUser(user: User): User
    fun findUser(mailbox: String): User?
    fun saveSession(session: Session): Session
    fun findSession(token: String): Session?
    fun saveMessage(message: MailMessage): MailMessage
    fun queueRecipients(messageId: String, recipients: List<String>)
    fun inbox(mailbox: String): List<MailMessage>
    fun sent(mailbox: String): List<MailMessage>
    fun archive(mailbox: String): List<MailMessage>
    fun trash(mailbox: String): List<MailMessage>
    fun findMessageForMailbox(messageId: String, mailbox: String, includeDeleted: Boolean = false): MailMessage?
    fun search(mailbox: String, query: String, box: String? = null, limit: Int = 100): List<MailMessage>
    fun setArchived(mailbox: String, messageId: String, archived: Boolean)
    fun setDeleted(mailbox: String, messageId: String, deleted: Boolean)
    fun markRead(mailbox: String, messageId: String, read: Boolean = true)
    fun users(): List<User>
    fun messages(limit: Int = 100): List<MailMessage>
    fun queueItems(status: QueueStatus? = null): List<QueueItem>
    fun nextQueued(limit: Int = 20): List<QueueItem>
    fun markQueueDelivered(id: String)
    fun markQueueRetry(id: String, error: String, nextAttemptAt: Long)
    fun markQueueDead(id: String, error: String)
    fun close() {}
}
