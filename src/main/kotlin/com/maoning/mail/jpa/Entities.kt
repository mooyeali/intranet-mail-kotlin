package com.maoning.mail.jpa

import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.Id
import javax.persistence.Lob
import javax.persistence.Table

@Entity
@Table(name = "users")
class UserEntity(
    @Id
    @Column(length = 64)
    var id: String = "",
    @Column(nullable = false, unique = true, length = 64)
    var username: String = "",
    @Column(nullable = false, unique = true)
    var mailbox: String = "",
    @Column(name = "password_hash", nullable = false)
    var passwordHash: String = "",
    @Column(name = "created_at", nullable = false)
    var createdAt: Long = 0
)

@Entity
@Table(name = "sessions")
class SessionEntity(
    @Id
    var token: String = "",
    @Column(nullable = false)
    var mailbox: String = "",
    @Column(name = "created_at", nullable = false)
    var createdAt: Long = 0,
    @Column(name = "expires_at", nullable = false)
    var expiresAt: Long = 0
)

@Entity
@Table(name = "messages")
class MessageEntity(
    @Id
    @Column(length = 64)
    var id: String = "",
    @Column(name = "sender", nullable = false)
    var sender: String = "",
    @Lob
    @Column(nullable = false)
    var recipients: String = "[]",
    @Column(nullable = false, length = 500)
    var subject: String = "",
    @Lob
    @Column(nullable = false)
    var body: String = "",
    @Lob
    var raw: String? = null,
    @Lob
    @Column(nullable = false)
    var attachments: String = "[]",
    @Column(name = "read_flag", nullable = false)
    var readFlag: Boolean = false,
    @Column(name = "created_at", nullable = false)
    var createdAt: Long = 0
)

@Entity
@Table(name = "mailboxes")
class MailboxEntity(
    @Id
    @Column(length = 64)
    var id: String = "",
    @Column(nullable = false)
    var mailbox: String = "",
    @Column(name = "message_id", nullable = false, length = 64)
    var messageId: String = "",
    @Column(nullable = false, length = 16)
    var box: String = "",
    @Column(name = "created_at", nullable = false)
    var createdAt: Long = 0,
    @Column(nullable = false)
    var archived: Boolean = false,
    @Column(nullable = false)
    var deleted: Boolean = false,
    @Column(name = "read_flag", nullable = false)
    var readFlag: Boolean = false
)

@Entity
@Table(name = "queue")
class QueueEntity(
    @Id
    @Column(length = 64)
    var id: String = "",
    @Column(name = "message_id", nullable = false, length = 64)
    var messageId: String = "",
    @Column(nullable = false)
    var recipient: String = "",
    @Column(nullable = false, length = 32)
    var status: String = "QUEUED",
    @Column(nullable = false)
    var attempts: Int = 0,
    @Lob
    @Column(name = "last_error")
    var lastError: String? = null,
    @Column(name = "next_attempt_at", nullable = false)
    var nextAttemptAt: Long = 0,
    @Column(name = "created_at", nullable = false)
    var createdAt: Long = 0,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = 0
)

@Entity
@Table(name = "audit_events")
class AuditEventEntity(
    @Id
    @Column(length = 64)
    var id: String = "",
    var actor: String? = null,
    @Column(nullable = false, length = 128)
    var action: String = "",
    var target: String? = null,
    @Lob
    var detail: String? = null,
    @Column(length = 64)
    var ip: String? = null,
    @Column(name = "created_at", nullable = false)
    var createdAt: Long = 0
)

@Entity
@Table(name = "login_attempts")
class LoginAttemptEntity(
    @Id
    @Column(length = 64)
    var id: String = "",
    @Column(nullable = false)
    var username: String = "",
    @Column(nullable = false, length = 64)
    var ip: String = "",
    @Column(nullable = false)
    var success: Boolean = false,
    @Column(name = "created_at", nullable = false)
    var createdAt: Long = 0
)
