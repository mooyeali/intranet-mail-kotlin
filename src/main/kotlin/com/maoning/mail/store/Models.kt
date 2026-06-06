package com.maoning.mail.store

import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

@Serializable
data class User(
    val id: String = UUID.randomUUID().toString(),
    val username: String,
    val mailbox: String,
    val passwordHash: String,
    val createdAt: Long = Instant.now().toEpochMilli()
)

@Serializable
data class Attachment(
    val id: String = UUID.randomUUID().toString(),
    val fileName: String,
    val contentType: String,
    val size: Long,
    val path: String? = null
)

@Serializable
data class MailMessage(
    val id: String = UUID.randomUUID().toString(),
    val from: String,
    val to: List<String>,
    val subject: String,
    val body: String,
    val raw: String? = null,
    val attachments: List<Attachment> = emptyList(),
    val read: Boolean = false,
    val createdAt: Long = Instant.now().toEpochMilli()
)

@Serializable
data class MailboxState(
    val messageId: String,
    val mailbox: String,
    val box: String,
    val read: Boolean,
    val archived: Boolean,
    val deleted: Boolean
)

@Serializable
data class Session(
    val token: String,
    val mailbox: String,
    val createdAt: Long = Instant.now().toEpochMilli(),
    val expiresAt: Long = Instant.now().plusSeconds(24 * 3600).toEpochMilli()
)

@Serializable
enum class QueueStatus { QUEUED, DELIVERED, RETRY, DEAD }

@Serializable
data class QueueItem(
    val id: String = UUID.randomUUID().toString(),
    val messageId: String,
    val recipient: String,
    val status: QueueStatus = QueueStatus.QUEUED,
    val attempts: Int = 0,
    val lastError: String? = null,
    val nextAttemptAt: Long = Instant.now().toEpochMilli(),
    val createdAt: Long = Instant.now().toEpochMilli(),
    val updatedAt: Long = Instant.now().toEpochMilli()
)
