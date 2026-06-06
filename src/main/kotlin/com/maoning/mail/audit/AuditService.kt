package com.maoning.mail.audit

import com.maoning.mail.jpa.AuditEventEntity
import com.maoning.mail.jpa.AuditEventRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

@Service
class AuditService(
    private val repository: AuditEventRepository? = null,
    private val dataSource: DataSource? = null
) {
    constructor(dataSource: DataSource) : this(null, dataSource)

    private fun conn(): Connection = requireNotNull(dataSource).connection

    fun record(actor: String?, action: String, target: String? = null, detail: String? = null, ip: String? = null) {
        val now = Instant.now().toEpochMilli()
        if (repository != null) {
            repository.save(AuditEventEntity(UUID.randomUUID().toString(), actor, action, target, detail, ip, now))
            return
        }
        if (dataSource != null) {
            conn().use { c ->
                c.prepareStatement("insert into audit_events(id, actor, action, target, detail, ip, created_at) values(?,?,?,?,?,?,?)").use { ps ->
                    ps.setString(1, UUID.randomUUID().toString())
                    ps.setString(2, actor)
                    ps.setString(3, action)
                    ps.setString(4, target)
                    ps.setString(5, detail)
                    ps.setString(6, ip)
                    ps.setLong(7, now)
                    ps.executeUpdate()
                }
            }
        }
    }

    fun recent(limit: Int = 100): List<AuditEvent> {
        if (repository != null) {
            return repository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, limit))
                .map { AuditEvent(it.actor, it.action, it.target, it.detail, it.ip, it.createdAt) }
        }
        if (dataSource != null) {
            return conn().use { c ->
                c.prepareStatement("select * from audit_events order by created_at desc limit ?").use { ps ->
                    ps.setInt(1, limit)
                    ps.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) add(AuditEvent(rs.getString("actor"), rs.getString("action"), rs.getString("target"), rs.getString("detail"), rs.getString("ip"), rs.getLong("created_at")))
                        }
                    }
                }
            }
        }
        return emptyList()
    }
}

data class AuditEvent(val actor: String?, val action: String, val target: String?, val detail: String?, val ip: String?, val createdAt: Long)
