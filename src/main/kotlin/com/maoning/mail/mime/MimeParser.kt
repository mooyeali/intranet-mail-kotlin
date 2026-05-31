package com.maoning.mail.mime

import com.maoning.mail.attachment.AttachmentStorage
import com.maoning.mail.store.Attachment
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Properties

class MimeParser(
    private val attachmentStorage: AttachmentStorage,
    private val maxAttachmentBytes: Long = 10L * 1024 * 1024,
    private val maxTotalAttachmentBytes: Long = 20L * 1024 * 1024
) {
    data class ParsedMail(val subject: String, val body: String, val attachments: List<Attachment>)

    fun parse(raw: String): ParsedMail {
        val session = Session.getInstance(Properties())
        val message = MimeMessage(session, ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)))
        val attachments = mutableListOf<Attachment>()
        val bodies = mutableListOf<String>()
        val totalAttachmentBytes = longArrayOf(0L)
        collect(message, bodies, attachments, totalAttachmentBytes)
        return ParsedMail(
            subject = message.subject ?: "(no subject)",
            body = bodies.joinToString("\n").ifBlank { "(empty)" },
            attachments = attachments
        )
    }

    private fun collect(
        part: Part,
        bodies: MutableList<String>,
        attachments: MutableList<Attachment>,
        totalAttachmentBytes: LongArray
    ) {
        val disposition = part.disposition.orEmpty()
        val fileName = part.fileName
        val isAttachment = disposition.equals(Part.ATTACHMENT, ignoreCase = true) || fileName != null
        when {
            part.isMimeType("text/plain") && !isAttachment -> bodies += part.content.toString()
            part.isMimeType("text/html") && !isAttachment && bodies.isEmpty() -> bodies += part.content.toString().replace(Regex("<[^>]+>"), "")
            part.isMimeType("multipart/*") -> {
                val multipart = part.content as Multipart
                for (i in 0 until multipart.count) collect(multipart.getBodyPart(i), bodies, attachments, totalAttachmentBytes)
            }
            isAttachment -> {
                val bytes = part.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var size = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        size += read
                        require(size <= maxAttachmentBytes) { "Attachment exceeds max size of $maxAttachmentBytes bytes" }
                        require(totalAttachmentBytes[0] + size <= maxTotalAttachmentBytes) { "Attachments exceed total max size of $maxTotalAttachmentBytes bytes" }
                        out.write(buffer, 0, read)
                    }
                    totalAttachmentBytes[0] += size
                    out.toByteArray()
                }
                val stored = attachmentStorage.save(bytes, fileName ?: "attachment.bin")
                attachments += Attachment(
                    fileName = fileName ?: "attachment.bin",
                    contentType = part.contentType.substringBefore(';').trim(),
                    size = stored.size,
                    path = stored.path
                )
            }
        }
    }
}
