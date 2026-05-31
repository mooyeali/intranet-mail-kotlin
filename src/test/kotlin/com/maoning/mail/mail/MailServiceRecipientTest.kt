package com.maoning.mail.mail

import com.maoning.mail.attachment.AttachmentStorage
import com.maoning.mail.audit.AuditService
import com.maoning.mail.auth.AuthService
import com.maoning.mail.config.AppConfig
import com.maoning.mail.db.DataSourceFactory
import com.maoning.mail.db.H2MailStore
import com.maoning.mail.mime.MimeParser
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class MailServiceRecipientTest {
    @Test
    fun rejectsUnknownRecipientsBeforeQueueing() {
        val workDir = Files.createTempDirectory("mail-recipient-test")
        val dbUrl = "jdbc:h2:${workDir.resolve("mail-db")};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
        val config = AppConfig(
            domain = "test.local",
            h2Url = dbUrl,
            attachmentDir = workDir.resolve("attachments").toString(),
            adminToken = "test-admin-token"
        )
        val dataSource = DataSourceFactory.hikari(config.h2Url, config.h2User, config.h2Password)
        val store = H2MailStore(config.domain, dataSource)
        val auth = AuthService(store)
        val audit = AuditService(dataSource)
        val mail = MailService(store, MimeParser(AttachmentStorage(config.attachmentDir)), audit)

        auth.register("alice", "alicePass123")

        assertFailsWith<IllegalArgumentException> {
            mail.send("alice", listOf("missing"), "Hello", "Body")
        }
    }
}
