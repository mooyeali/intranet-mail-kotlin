package com.maoning.mail.integration

import com.maoning.mail.admin.AdminAuthService
import com.maoning.mail.api.MailController
import com.maoning.mail.api.MailboxStateResponse
import com.maoning.mail.attachment.AttachmentStorage
import com.maoning.mail.audit.AuditService
import com.maoning.mail.auth.AuthService
import com.maoning.mail.config.AppConfig
import com.maoning.mail.db.DataSourceFactory
import com.maoning.mail.db.H2MailStore
import com.maoning.mail.mail.MailService
import com.maoning.mail.mime.MimeParser
import com.maoning.mail.queue.MailQueueWorker
import com.maoning.mail.store.Attachment
import com.maoning.mail.store.MailMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.nio.file.Files
import javax.servlet.http.HttpServletRequest

class MailFlowIT {
    @Test
    fun apiFlowRegistersLogsInSendsDrainsSearchesDownloadsAndMovesMailboxState() {
        val workDir = Files.createTempDirectory("mail-api-flow-it")
        val dbUrl = "jdbc:h2:${workDir.resolve("mail-db")};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
        val config = AppConfig(
            domain = "api.local",
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
        val controller = MailController(config, auth, mail, store, worker, audit, attachments, AdminAuthService(config))

        try {
            controller.register(com.maoning.mail.api.RegisterRequest("alice", "alice-test-pw"), fakeRequest())
            controller.register(com.maoning.mail.api.RegisterRequest("bob", "bob-test-pw"), fakeRequest())
            val login = controller.login(com.maoning.mail.api.LoginRequest("alice", "alice-test-pw"), fakeRequest())
            assertEquals(org.springframework.http.HttpStatus.OK, login.statusCode)
            val token = (login.body as com.maoning.mail.api.LoginResponse).token
            val authHeader = "Bearer $token"

            val sentResponse = controller.send(
                authHeader,
                com.maoning.mail.api.SendMailRequest(listOf("bob"), "API integration hello", "searchable body from api flow")
            )
            assertEquals(org.springframework.http.HttpStatus.CREATED, sentResponse.statusCode)
            val sent = assertIs<MailMessage>(sentResponse.body)
            assertEquals(1, controller.sent(authHeader, 100, 0).body.asMessages().size)

            worker.drain()
            val bobLogin = controller.login(com.maoning.mail.api.LoginRequest("bob", "bob-test-pw"), fakeRequest())
            val bobHeader = "Bearer ${(bobLogin.body as com.maoning.mail.api.LoginResponse).token}"
            assertTrue(controller.inbox(bobHeader, 100, 0).body.asMessages().any { it.id == sent.id })
            assertEquals(sent.id, assertIs<MailMessage>(controller.messageDetail(bobHeader, sent.id).body).id)
            assertTrue(controller.search(bobHeader, "searchable", null, 500).body.asMessages().any { it.id == sent.id })

            val storedAttachment = attachments.save("hello attachment".toByteArray(), "note.txt")
            val attachment = store.saveMessage(
                MailMessage(
                    from = "alice@api.local",
                    to = listOf("bob@api.local"),
                    subject = "Attachment hello",
                    body = "has attachment",
                    attachments = listOf(
                        Attachment(
                            fileName = "note.txt",
                            contentType = "text/plain",
                            size = storedAttachment.size,
                            path = storedAttachment.path
                        )
                    )
                )
            )
            store.queueRecipients(attachment.id, listOf("bob@api.local"))
            worker.drain()
            val attachmentResponse = controller.attachment(bobHeader, attachment.id, attachment.attachments.single().id, fakeRequest())
            assertEquals(org.springframework.http.HttpStatus.OK, attachmentResponse.statusCode)

            assertEquals(MailboxStateResponse(sent.id, "bob@api.local", "inbox", read = true, archived = false, deleted = false), controller.markRead(bobHeader, sent.id).body)
            assertEquals(MailboxStateResponse(sent.id, "bob@api.local", "inbox", read = true, archived = true, deleted = false), controller.archiveMessage(bobHeader, sent.id).body)
            assertTrue(controller.archive(bobHeader, 100, 0).body.asMessages().any { it.id == sent.id })
            assertTrue(controller.inbox(bobHeader, 100, 0).body.asMessages().none { it.id == sent.id })
            assertEquals(MailboxStateResponse(sent.id, "bob@api.local", "inbox", read = true, archived = true, deleted = true), controller.trashMessage(bobHeader, sent.id).body)
            assertTrue(controller.trash(bobHeader, 100, 0).body.asMessages().any { it.id == sent.id })
            assertEquals(MailboxStateResponse(sent.id, "bob@api.local", "inbox", read = true, archived = true, deleted = false), controller.restoreMessage(bobHeader, sent.id).body)
            assertEquals(MailboxStateResponse(sent.id, "bob@api.local", "inbox", read = false, archived = true, deleted = false), controller.markUnread(bobHeader, sent.id).body)
            assertEquals(MailboxStateResponse(sent.id, "bob@api.local", "inbox", read = false, archived = false, deleted = false), controller.unarchiveMessage(bobHeader, sent.id).body)
        } finally {
            dataSource.close()
        }
    }

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

        auth.register("alice", "alice-test-pw")
        auth.register("bob", "bob-test-pw")
        val aliceSession = auth.login("alice", "alice-test-pw")
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

private fun Any?.asMessages(): List<MailMessage> = assertIs<List<MailMessage>>(this)

private fun fakeRequest(): HttpServletRequest = org.mockito.Mockito.mock(HttpServletRequest::class.java).also {
    org.mockito.Mockito.`when`(it.remoteAddr).thenReturn("127.0.0.1")
}
