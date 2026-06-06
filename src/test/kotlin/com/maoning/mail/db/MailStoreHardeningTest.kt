package com.maoning.mail.db

import com.maoning.mail.config.AppConfig
import com.maoning.mail.store.MailMessage
import com.maoning.mail.store.Session
import com.maoning.mail.store.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailStoreHardeningTest {
    private fun dataSource(name: String = java.util.UUID.randomUUID().toString()) =
        DataSourceFactory.hikari(AppConfig().copy(h2Url = "jdbc:h2:mem:$name;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1").h2Url, "sa", "")
    @Test
    fun h2StorePaginatesMailboxListsAndPreventsDuplicateMailboxRows() {
        H2MailStore("test.local", dataSource()).use { store ->
            store.createUser(User(username = "alice", mailbox = "alice@test.local", passwordHash = "pw"))
            store.createUser(User(username = "bob", mailbox = "bob@test.local", passwordHash = "pw"))
            repeat(3) { index ->
                store.saveMessage(
                    MailMessage(
                        id = "m$index",
                        from = "alice@test.local",
                        to = listOf("bob@test.local"),
                        subject = "Subject $index",
                        body = "Body $index",
                        createdAt = 1000L + index
                    )
                )
            }
            store.queueRecipients("m2", listOf("bob@test.local"))

            assertEquals(listOf("m2", "m1"), store.sent("alice@test.local", limit = 2, offset = 0).map { it.id })
            assertEquals(listOf("m1"), store.sent("alice@test.local", limit = 1, offset = 1).map { it.id })

            val queueItem = store.nextQueued(1).single()
            assertTrue(store.markQueueInProgress(queueItem.id, queueItem.attempts))
            store.deliverQueued(queueItem)
            store.deliverQueued(queueItem)
            assertEquals(1, store.inbox("bob@test.local", limit = 20, offset = 0).size)
        }
    }

    @Test
    fun h2StoreAtomicallyClaimsQueueAndDeletesExpiredSessions() {
        H2MailStore("test.local", dataSource()).use { store ->
            store.createUser(User(username = "alice", mailbox = "alice@test.local", passwordHash = "pw"))
            val message = store.saveMessage(MailMessage(id = "m1", from = "alice@test.local", to = listOf("bob@test.local"), subject = "S", body = "B"))
            store.queueRecipients(message.id, listOf("bob@test.local"))
            val item = store.nextQueued(1).single()

            assertTrue(store.markQueueInProgress(item.id, item.attempts))
            assertFalse(store.markQueueInProgress(item.id, item.attempts))

            store.saveSession(Session("expired", "alice@test.local", createdAt = 1, expiresAt = 10))
            store.saveSession(Session("active", "alice@test.local", createdAt = 1, expiresAt = Long.MAX_VALUE))
            assertEquals(1, store.deleteExpiredSessions(now = 100))
            assertNull(store.findSession("expired"))
            assertEquals("active", store.findSession("active")?.token)
        }
    }

    @Test
    fun h2StoreHashesSessionTokensAtRestAndSupportsRevocation() {
        H2MailStore("test.local", dataSource()).use { store ->
            val token = "plain-token-for-client"
            store.saveSession(Session(token, "alice@test.local", createdAt = 1, expiresAt = Long.MAX_VALUE))

            assertEquals("alice@test.local", store.findSession(token)?.mailbox)
            assertNull(rawSessionToken(store, token))
            assertEquals(1, rawSessionCount(store))
            assertEquals(1, store.revokeSession(token))
            assertNull(store.findSession(token))
        }
    }

    private fun rawSessionToken(store: H2MailStore, token: String): String? =
        store.dataSource.connection.use { c ->
            c.prepareStatement("select token from sessions where token=?").use { ps ->
                ps.setString(1, token)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    private fun rawSessionCount(store: H2MailStore): Int =
        store.dataSource.connection.use { c ->
            c.prepareStatement("select count(*) from sessions").use { ps ->
                ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
            }
        }
}
