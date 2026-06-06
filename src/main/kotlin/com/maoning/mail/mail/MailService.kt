package com.maoning.mail.mail

import com.maoning.mail.audit.AuditService
import com.maoning.mail.config.AppConfig
import com.maoning.mail.mime.MimeParser
import com.maoning.mail.store.MailMessage
import com.maoning.mail.store.MailStore

class MailService(
    private val store: MailStore,
    private val mimeParser: MimeParser,
    private val auditService: AuditService,
    private val config: AppConfig = AppConfig()
) {
    fun send(from: String, to: List<String>, subject: String, body: String): MailMessage {
        require(to.isNotEmpty()) { "At least one recipient is required" }
        require(to.size <= config.maxRecipients) { "Recipient count exceeds MAX_RECIPIENTS (${config.maxRecipients})" }
        require(subject.length <= 200) { "Subject is too long" }
        val normalizedFrom = store.normalizeMailbox(from)
        require(store.userExists(normalizedFrom)) { "Sender not found: $normalizedFrom" }
        val normalizedTo = to.map { store.normalizeMailbox(it) }
        normalizedTo.forEach { require(store.userExists(it)) { "Recipient not found: $it" } }
        val message = store.saveMessage(
            MailMessage(
                from = normalizedFrom,
                to = normalizedTo,
                subject = subject,
                body = body
            )
        )
        store.queueRecipients(message.id, normalizedTo)
        auditService.record(normalizedFrom, "MAIL_SEND_REST", message.id, "to=${normalizedTo.joinToString()}")
        return message
    }

    fun receiveSmtp(from: String, to: List<String>, raw: String): MailMessage {
        require(to.isNotEmpty()) { "At least one recipient is required" }
        require(to.size <= config.maxRecipients) { "Recipient count exceeds MAX_RECIPIENTS (${config.maxRecipients})" }
        val parsed = mimeParser.parse(raw)
        val normalizedTo = to.map { store.normalizeMailbox(it) }
        normalizedTo.forEach { require(store.userExists(it)) { "Recipient not found: $it" } }
        val normalizedFrom = store.normalizeMailbox(from)
        require(store.userExists(normalizedFrom)) { "Sender not found: $normalizedFrom" }
        val message = store.saveMessage(
            MailMessage(
                from = normalizedFrom,
                to = normalizedTo,
                subject = parsed.subject,
                body = parsed.body,
                raw = raw,
                attachments = parsed.attachments
            )
        )
        store.queueRecipients(message.id, normalizedTo)
        auditService.record(normalizedFrom, "MAIL_SEND_SMTP", message.id, "to=${normalizedTo.joinToString()}; attachments=${parsed.attachments.size}")
        return message
    }

    fun inbox(mailbox: String, limit: Int = 100, offset: Int = 0) = store.inbox(mailbox, limit, offset)
    fun sent(mailbox: String, limit: Int = 100, offset: Int = 0) = store.sent(mailbox, limit, offset)
}
