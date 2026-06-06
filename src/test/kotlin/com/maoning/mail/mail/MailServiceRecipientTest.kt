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
import kotlin.test.assertEquals
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

        auth.register("alice", "alice-test-pw")

        assertFailsWith<IllegalArgumentException> {
            mail.send("alice", listOf("missing"), "Hello", "Body")
        }
    }
    @Test
    fun rejectsRestSendWhenRecipientCountExceedsLimit() {
        val fixture = fixture(AppConfig(domain = "test.local", maxRecipients = 2))
        fixture.auth.register("alice", "alice-test-pw")
        fixture.auth.register("bob", "bob-test-pw")
        fixture.auth.register("carol", "carolPass123")

        assertFailsWith<IllegalArgumentException> {
            fixture.mail.send("alice", listOf("bob", "carol", "alice"), "Hello", "Body")
        }
    }

    @Test
    fun rejectsUnknownSenderBeforeQueueingRecipients() {
        val fixture = fixture(AppConfig(domain = "test.local"))
        fixture.auth.register("bob", "bob-test-pw")

        assertFailsWith<IllegalArgumentException> {
            fixture.mail.send("missing", listOf("bob"), "Hello", "Body")
        }
    }

    @Test
    fun preservesDuplicateAndSelfRecipientsAsExplicitDeliveryRows() {
        val fixture = fixture(AppConfig(domain = "test.local"))
        fixture.auth.register("alice", "alice-test-pw")
        fixture.auth.register("bob", "bob-test-pw")

        val message = fixture.mail.send("alice", listOf("bob", "bob@test.local", "alice"), "Hello", "Body")

        assertEquals(listOf("bob@test.local", "bob@test.local", "alice@test.local"), message.to)
        assertEquals(listOf("bob@test.local", "bob@test.local", "alice@test.local"), fixture.store.queueItemsForMessage(message.id).map { it.recipient })
    }

    @Test
    fun rejectsSmtpReceiveWhenRecipientCountExceedsLimit() {
        val fixture = fixture(AppConfig(domain = "test.local", maxRecipients = 2))
        fixture.auth.register("alice", "alice-test-pw")
        fixture.auth.register("bob", "bob-test-pw")
        fixture.auth.register("carol", "carolPass123")

        assertFailsWith<IllegalArgumentException> {
            fixture.mail.receiveSmtp("alice", listOf("bob", "carol", "alice"), "Subject: Hello\n\nBody")
        }
    }

    private data class Fixture(val auth: AuthService, val mail: MailService, val store: H2MailStore)

    private fun fixture(baseConfig: AppConfig): Fixture {
        val workDir = Files.createTempDirectory("mail-recipient-limit-test")
        val config = baseConfig.copy(
            h2Url = "jdbc:h2:${workDir.resolve("mail-db")};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
            attachmentDir = workDir.resolve("attachments").toString(),
            adminToken = "test-admin-token"
        )
        val dataSource = DataSourceFactory.hikari(config.h2Url, config.h2User, config.h2Password)
        val store = H2MailStore(config.domain, dataSource)
        val auth = AuthService(store)
        val audit = AuditService(dataSource)
        val mail = MailService(store, MimeParser(AttachmentStorage(config.attachmentDir)), audit, config)
        return Fixture(auth, mail, store)
    }
}
