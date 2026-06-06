package com.maoning.mail.jpa

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

data class MailboxMessageRow(val message: MessageEntity, val mailboxRead: Boolean)

@Repository
interface UserRepository : JpaRepository<UserEntity, String> {
    fun findByMailboxOrUsername(mailbox: String, username: String): UserEntity?
    fun findAllByOrderByCreatedAtDesc(): List<UserEntity>
}

@Repository
interface SessionRepository : JpaRepository<SessionEntity, String> {
    @Modifying
    @Query("delete from SessionEntity s where s.expiresAt < :now")
    fun deleteExpired(@Param("now") now: Long): Int

    @Modifying
    @Query("delete from SessionEntity s where s.token = :tokenHash")
    fun revokeByTokenHash(@Param("tokenHash") tokenHash: String): Int
}

@Repository
interface MessageRepository : JpaRepository<MessageEntity, String> {
    fun findAllByOrderByCreatedAtDesc(pageable: Pageable): List<MessageEntity>
}

@Repository
interface MailboxRepository : JpaRepository<MailboxEntity, String> {
    @Query("select b from MailboxEntity b where b.mailbox = :mailbox and b.deleted = :deleted and (:box is null or b.box = :box) and (:archived is null or b.archived = :archived) order by b.createdAt desc")
    fun findMailboxRows(@Param("mailbox") mailbox: String, @Param("box") box: String?, @Param("archived") archived: Boolean?, @Param("deleted") deleted: Boolean): List<MailboxEntity>

    @Query("select new com.maoning.mail.jpa.MailboxMessageRow(m, b.readFlag) from MailboxEntity b join MessageEntity m on m.id = b.messageId where b.mailbox = :mailbox and b.deleted = :deleted and (:box is null or b.box = :box) and (:archived is null or b.archived = :archived) order by b.createdAt desc")
    fun findMailboxMessageRows(@Param("mailbox") mailbox: String, @Param("box") box: String?, @Param("archived") archived: Boolean?, @Param("deleted") deleted: Boolean, pageable: Pageable): List<MailboxMessageRow>

    @Query("select new com.maoning.mail.jpa.MailboxMessageRow(m, b.readFlag) from MailboxEntity b join MessageEntity m on m.id = b.messageId where b.mailbox = :mailbox and b.deleted = false and (:box is null or b.box = :box) and (:query = '' or lower(m.subject) like :pattern or lower(m.body) like :pattern or lower(m.sender) like :pattern or lower(m.recipients) like :pattern) order by b.createdAt desc")
    fun searchMailboxMessageRows(@Param("mailbox") mailbox: String, @Param("query") query: String, @Param("pattern") pattern: String, @Param("box") box: String?, pageable: Pageable): List<MailboxMessageRow>

    fun findFirstByMessageIdAndMailboxAndDeletedFalse(messageId: String, mailbox: String): MailboxEntity?
    fun findFirstByMessageIdAndMailbox(messageId: String, mailbox: String): MailboxEntity?
    fun findFirstByMessageIdAndMailboxAndBoxAndDeletedFalse(messageId: String, mailbox: String, box: String): MailboxEntity?

    @Modifying
    @Query("update MailboxEntity b set b.archived = :archived where b.mailbox = :mailbox and b.messageId = :messageId")
    fun updateArchived(@Param("mailbox") mailbox: String, @Param("messageId") messageId: String, @Param("archived") archived: Boolean): Int

    @Modifying
    @Query("update MailboxEntity b set b.deleted = :deleted where b.mailbox = :mailbox and b.messageId = :messageId")
    fun updateDeleted(@Param("mailbox") mailbox: String, @Param("messageId") messageId: String, @Param("deleted") deleted: Boolean): Int

    @Modifying
    @Query("update MailboxEntity b set b.readFlag = :read where b.mailbox = :mailbox and b.messageId = :messageId")
    fun updateRead(@Param("mailbox") mailbox: String, @Param("messageId") messageId: String, @Param("read") read: Boolean): Int
}

@Repository
interface QueueRepository : JpaRepository<QueueEntity, String> {
    fun findAllByOrderByUpdatedAtDesc(): List<QueueEntity>
    fun findByStatusOrderByUpdatedAtDesc(status: String): List<QueueEntity>
    fun findByMessageIdOrderByUpdatedAtDesc(messageId: String): List<QueueEntity>

    @Query("select q from QueueEntity q where q.status in :statuses and q.nextAttemptAt <= :now order by q.nextAttemptAt asc")
    fun nextQueued(@Param("statuses") statuses: Collection<String>, @Param("now") now: Long, pageable: Pageable): List<QueueEntity>

    @Modifying
    @Query("update QueueEntity q set q.status = 'RETRY', q.attempts = :nextAttempts, q.updatedAt = :now where q.id = :id and q.status in :statuses and q.attempts = :expectedAttempts and q.nextAttemptAt <= :now")
    fun claimReady(@Param("id") id: String, @Param("expectedAttempts") expectedAttempts: Int, @Param("nextAttempts") nextAttempts: Int, @Param("now") now: Long, @Param("statuses") statuses: Collection<String>): Int
}

@Repository
interface AuditEventRepository : JpaRepository<AuditEventEntity, String> {
    fun findAllByOrderByCreatedAtDesc(pageable: Pageable): List<AuditEventEntity>
}

@Repository
interface LoginAttemptRepository : JpaRepository<LoginAttemptEntity, String> {
    fun countByUsernameAndIpAndSuccessFalseAndCreatedAtGreaterThanEqual(username: String, ip: String, createdAt: Long): Long
}
