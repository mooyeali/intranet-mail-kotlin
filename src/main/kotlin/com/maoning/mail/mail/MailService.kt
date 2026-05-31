package com.maoning.mail.mail

import com.maoning.mail.audit.AuditService
import com.maoning.mail.mime.MimeParser
import com.maoning.mail.store.MailMessage
import com.maoning.mail.store.MailStore

class MailService(
    private val store: MailStore,
    private val mimeParser: MimeParser,
    private val auditService: AuditService
) {
    fun send(from: String, to: List<String>, subject: String, body: String): MailMessage {
        require(to.isNotEmpty()) { "At least one recipient is required" }
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

    fun inbox(mailbox: String) = store.inbox(mailbox)
    fun sent(mailbox: String) = store.sent(mailbox)
}
