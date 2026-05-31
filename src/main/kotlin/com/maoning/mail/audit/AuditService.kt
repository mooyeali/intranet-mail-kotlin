package com.maoning.mail.audit

import java.sql.Connection
import javax.sql.DataSource
import java.time.Instant
import java.util.UUID

class AuditService(private val dataSource: DataSource) {
    private fun conn(): Connection = dataSource.connection

    fun record(actor: String?, action: String, target: String? = null, detail: String? = null, ip: String? = null) {
        conn().use { c ->
            c.prepareStatement("insert into audit_events(id, actor, action, target, detail, ip, created_at) values(?,?,?,?,?,?,?)").use { ps ->
                ps.setString(1, UUID.randomUUID().toString())
                ps.setString(2, actor)
                ps.setString(3, action)
                ps.setString(4, target)
                ps.setString(5, detail)
                ps.setString(6, ip)
                ps.setLong(7, Instant.now().toEpochMilli())
                ps.executeUpdate()
            }
        }
    }

    fun recent(limit: Int = 100): List<AuditEvent> = conn().use { c ->
        c.prepareStatement("select * from audit_events order by created_at desc limit ?").use { ps ->
            ps.setInt(1, limit)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(AuditEvent(rs.getString("actor"), rs.getString("action"), rs.getString("target"), rs.getString("detail"), rs.getString("ip"), rs.getLong("created_at")))
                    }
                }
            }
        }
    }
}

data class AuditEvent(val actor: String?, val action: String, val target: String?, val detail: String?, val ip: String?, val createdAt: Long)
