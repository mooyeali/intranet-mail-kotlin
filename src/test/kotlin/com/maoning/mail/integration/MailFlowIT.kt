package com.maoning.mail.integration

import com.maoning.mail.attachment.AttachmentStorage
import com.maoning.mail.audit.AuditService
import com.maoning.mail.auth.AuthService
import com.maoning.mail.config.AppConfig
import com.maoning.mail.db.DataSourceFactory
import com.maoning.mail.db.H2MailStore
import com.maoning.mail.mail.MailService
import com.maoning.mail.mime.MimeParser
import com.maoning.mail.queue.MailQueueWorker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.nio.file.Files

class MailFlowIT {
    @Test
    fun fullMailFlowPersistsDeliversSearchesArchivesAndRestores() {
        val workDir = Files.createTempDirectory("mail-flow-it")
        val dbUrl = "jdbc:h2:${workDir.resolve("mail-db")};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
        val config = AppConfig(
            domain = "it.local",
            h2Url = dbUrl,
            attachmentDir = workDir.resolve("attachments").toString(),
            maxQueueAttempts = 2
        )
        val dataSource = DataSourceFactory.hikari(config.h2Url, config.h2User, config.h2Password)
        val store = H2MailStore(config.domain, dataSource)
        val auth = AuthService(store)
        val audit = AuditService(dataSource)
        val attachments = AttachmentStorage(config.attachmentDir)
        val mail = MailService(store, MimeParser(attachments), audit)
        val worker = MailQueueWorker(store, config)

        auth.register("alice", "alicePass123")
        auth.register("bob", "bobPass123")
        val aliceSession = auth.login("alice", "alicePass123")
        assertNotNull(store.findSession(aliceSession.token))

        val sent = mail.send("alice", listOf("bob"), "Project Hello", "hello from integration test")
        assertEquals(1, store.sent("alice").size)
        assertEquals(1, store.queueItems().size)

        worker.drain()
        assertEquals(1, store.inbox("bob").size)
        assertTrue(store.search("bob", "integration").any { it.id == sent.id })

        store.setArchived("bob", sent.id, true)
        assertTrue(store.inbox("bob").none { it.id == sent.id })
        assertTrue(store.archive("bob").any { it.id == sent.id })

        store.setDeleted("bob", sent.id, true)
        assertTrue(store.archive("bob").none { it.id == sent.id })
        assertTrue(store.trash("bob").any { it.id == sent.id })

        store.setDeleted("bob", sent.id, false)
        store.setArchived("bob", sent.id, false)
        assertTrue(store.inbox("bob").any { it.id == sent.id })
    }
}
