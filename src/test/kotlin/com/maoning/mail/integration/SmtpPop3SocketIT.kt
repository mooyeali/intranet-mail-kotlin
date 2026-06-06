package com.maoning.mail.integration

import com.maoning.mail.attachment.AttachmentStorage
import com.maoning.mail.audit.AuditService
import com.maoning.mail.auth.AuthService
import com.maoning.mail.config.AppConfig
import com.maoning.mail.db.DataSourceFactory
import com.maoning.mail.db.H2MailStore
import com.maoning.mail.mail.MailService
import com.maoning.mail.mime.MimeParser
import com.maoning.mail.pop3.Pop3Server
import com.maoning.mail.queue.MailQueueWorker
import com.maoning.mail.security.LoginRateLimiter
import com.maoning.mail.smtp.SmtpServer
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class SmtpPop3SocketIT {
    @Test
    fun smtpDoesNotAdvertiseOrAcceptAuthBeforeTlsWhenRequired() {
        val workDir = Files.createTempDirectory("smtp-tls-required-it")
        val config = AppConfig(
            domain = "socket.local",
            h2Url = "jdbc:h2:${workDir.resolve("mail-db")};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
            attachmentDir = workDir.resolve("attachments").toString(),
            smtpHost = "127.0.0.1",
            smtpPort = freePort(),
            pop3Host = "127.0.0.1",
            pop3Port = freePort(),
            adminToken = "test-admin-token",
            adminSessionSecret = "valid-admin-session-secret-32-chars",
            socketTimeoutMillis = 5_000,
            maxMessageBytes = 4096,
            smtpRequireTlsForAuth = true
        )
        val dataSource = DataSourceFactory.hikari(config.h2Url, config.h2User, config.h2Password)
        val store = H2MailStore(config.domain, dataSource)
        val audit = AuditService(dataSource)
        val limiter = LoginRateLimiter(config, dataSource)
        val auth = AuthService(store, limiter)
        val mail = MailService(store, MimeParser(AttachmentStorage(config.attachmentDir)), audit)
        val smtp = SmtpServer(config, mail, auth, "mail.${config.domain}", store)

        try {
            auth.register("alice", "alice-test-pw")
            smtp.start()
            waitForPort(config.smtpPort)

            Socket(config.smtpHost, config.smtpPort).use { socket ->
                socket.soTimeout = 5_000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val writer = PrintWriter(socket.getOutputStream(), true)
                assertTrue(reader.readLine().startsWith("220"))
                writer.println("EHLO test")
                val capabilities = readEhloCapabilities(reader)
                assertFalse(capabilities.any { it.contains("AUTH", ignoreCase = true) })

                val authPayload = Base64.getEncoder().encodeToString(
                    byteArrayOf(0) +
                        "alice".toByteArray(Charsets.UTF_8) +
                        byteArrayOf(0) +
                        "alice-test-pw".toByteArray(Charsets.UTF_8)
                )
                writer.println("AUTH PLAIN $authPayload")
                assertTrue(reader.readLine().startsWith("538"))
                writer.println("QUIT")
            }
        } finally {
            smtp.stop()
            dataSource.close()
        }
    }

    @Test
    fun smtpRejectsWrongPasswordAndMailFromThatDoesNotMatchAuthenticatedMailbox() {
        val workDir = Files.createTempDirectory("smtp-auth-negative-it")
        val config = AppConfig(
            domain = "socket.local",
            h2Url = "jdbc:h2:${workDir.resolve("mail-db")};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
            attachmentDir = workDir.resolve("attachments").toString(),
            smtpHost = "127.0.0.1",
            smtpPort = freePort(),
            pop3Host = "127.0.0.1",
            pop3Port = freePort(),
            adminToken = "test-admin-token",
            adminSessionSecret = "valid-admin-session-secret-32-chars",
            socketTimeoutMillis = 5_000,
            maxMessageBytes = 4096,
            smtpRequireTlsForAuth = false,
            pop3RequireTlsForAuth = false
        )
        val dataSource = DataSourceFactory.hikari(config.h2Url, config.h2User, config.h2Password)
        val store = H2MailStore(config.domain, dataSource)
        val audit = AuditService(dataSource)
        val limiter = LoginRateLimiter(config, dataSource)
        val auth = AuthService(store, limiter)
        val mail = MailService(store, MimeParser(AttachmentStorage(config.attachmentDir)), audit)
        val smtp = SmtpServer(config, mail, auth, "mail.${config.domain}", store)

        try {
            auth.register("alice", "alice-test-pw")
            smtp.start()
            waitForPort(config.smtpPort)

            Socket(config.smtpHost, config.smtpPort).use { socket ->
                socket.soTimeout = 5_000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val writer = PrintWriter(socket.getOutputStream(), true)
                assertTrue(reader.readLine().startsWith("220"))
                writer.println("EHLO test")
                readUntil(reader, "250 ")

                val badPayload = Base64.getEncoder().encodeToString("\u0000alice\u0000wrongPass123".toByteArray(Charsets.UTF_8))
                writer.println("AUTH PLAIN $badPayload")
                assertTrue(reader.readLine().startsWith("535"))

                val goodPayload = Base64.getEncoder().encodeToString("\u0000alice\u0000alice-test-pw".toByteArray(Charsets.UTF_8))
                writer.println("AUTH PLAIN $goodPayload")
                assertTrue(reader.readLine().startsWith("235"))
                writer.println("MAIL FROM:<mallory@socket.local>")
                assertTrue(reader.readLine().startsWith("553"))
                writer.println("QUIT")
            }
        } finally {
            smtp.stop()
            dataSource.close()
        }
    }

    @Test
    fun smtpRejectsRecipientsBeyondConfiguredLimitBeforeData() {
        val workDir = Files.createTempDirectory("smtp-recipient-limit-it")
        val config = AppConfig(
            domain = "socket.local",
            h2Url = "jdbc:h2:${workDir.resolve("mail-db")};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
            attachmentDir = workDir.resolve("attachments").toString(),
            smtpHost = "127.0.0.1",
            smtpPort = freePort(),
            pop3Host = "127.0.0.1",
            pop3Port = freePort(),
            adminToken = "test-admin-token",
            adminSessionSecret = "valid-admin-session-secret-32-chars",
            socketTimeoutMillis = 5_000,
            maxMessageBytes = 4096,
            maxRecipients = 1,
            smtpRequireTlsForAuth = false,
            pop3RequireTlsForAuth = false
        )
        val dataSource = DataSourceFactory.hikari(config.h2Url, config.h2User, config.h2Password)
        val store = H2MailStore(config.domain, dataSource)
        val audit = AuditService(dataSource)
        val limiter = LoginRateLimiter(config, dataSource)
        val auth = AuthService(store, limiter)
        val mail = MailService(store, MimeParser(AttachmentStorage(config.attachmentDir)), audit, config)
        val smtp = SmtpServer(config, mail, auth, "mail.${config.domain}", store)

        try {
            auth.register("alice", "alice-test-pw")
            auth.register("bob", "bob-test-pw")
            auth.register("carol", "carolPass123")
            smtp.start()
            waitForPort(config.smtpPort)

            Socket(config.smtpHost, config.smtpPort).use { socket ->
                socket.soTimeout = 5_000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val writer = PrintWriter(socket.getOutputStream(), true)
                assertTrue(reader.readLine().startsWith("220"))
                writer.println("EHLO test")
                readUntil(reader, "250 ")
                val authPayload = Base64.getEncoder().encodeToString(
                    byteArrayOf(0) +
                        "alice".toByteArray(Charsets.UTF_8) +
                        byteArrayOf(0) +
                        "alice-test-pw".toByteArray(Charsets.UTF_8)
                )
                writer.println("AUTH PLAIN $authPayload")
                assertTrue(reader.readLine().startsWith("235"))
                writer.println("MAIL FROM:<alice@socket.local>")
                assertTrue(reader.readLine().startsWith("250"))
                writer.println("RCPT TO:<bob@socket.local>")
                assertTrue(reader.readLine().startsWith("250"))
                writer.println("RCPT TO:<carol@socket.local>")
                assertTrue(reader.readLine().startsWith("452"))
                writer.println("QUIT")
            }
        } finally {
            smtp.stop()
            dataSource.close()
        }
    }

    @Test
    fun pop3RejectsCleartextAuthenticationWhenTlsIsRequired() {
        val workDir = Files.createTempDirectory("pop3-tls-required-it")
        val config = AppConfig(
            domain = "socket.local",
            h2Url = "jdbc:h2:${workDir.resolve("mail-db")};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
            attachmentDir = workDir.resolve("attachments").toString(),
            smtpHost = "127.0.0.1",
            smtpPort = freePort(),
            pop3Host = "127.0.0.1",
            pop3Port = freePort(),
            adminToken = "test-admin-token",
            adminSessionSecret = "valid-admin-session-secret-32-chars",
            socketTimeoutMillis = 5_000,
            pop3RequireTlsForAuth = true
        )
        val dataSource = DataSourceFactory.hikari(config.h2Url, config.h2User, config.h2Password)
        val store = H2MailStore(config.domain, dataSource)
        val auth = AuthService(store, LoginRateLimiter(config, dataSource))
        val pop3 = Pop3Server(config, store, auth)

        try {
            auth.register("bob", "bob-test-pw")
            pop3.start()
            waitForPort(config.pop3Port)

            Socket(config.pop3Host, config.pop3Port).use { socket ->
                socket.soTimeout = 5_000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val writer = PrintWriter(socket.getOutputStream(), true)
                assertTrue(reader.readLine().startsWith("+OK"))
                writer.println("CAPA")
                val capabilities = readUntilDot(reader)
                assertFalse(capabilities.contains("STLS"), "server must not advertise STLS without configured TLS material")
                writer.println("USER bob")
                assertTrue(reader.readLine().startsWith("-ERR"))
                writer.println("PASS bob-test-pw")
                assertTrue(reader.readLine().startsWith("-ERR"))
                writer.println("QUIT")
            }
        } finally {
            pop3.stop()
            dataSource.close()
        }
    }

    @Test
    fun pop3AcceptsAuthenticationAfterStlsWhenTlsIsConfigured() {
        val workDir = Files.createTempDirectory("pop3-stls-it")
        val keyStore = workDir.resolve("pop3-test.p12")
        createSelfSignedKeyStore(keyStore.toString(), "changeit")
        val config = AppConfig(
            domain = "socket.local",
            h2Url = "jdbc:h2:${workDir.resolve("mail-db")};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
            attachmentDir = workDir.resolve("attachments").toString(),
            smtpHost = "127.0.0.1",
            smtpPort = freePort(),
            pop3Host = "127.0.0.1",
            pop3Port = freePort(),
            adminToken = "test-admin-token",
            adminSessionSecret = "valid-admin-session-secret-32-chars",
            socketTimeoutMillis = 5_000,
            tlsKeyStore = keyStore.toString(),
            tlsKeyStorePassword = "changeit",
            pop3RequireTlsForAuth = true
        )
        val dataSource = DataSourceFactory.hikari(config.h2Url, config.h2User, config.h2Password)
        val store = H2MailStore(config.domain, dataSource)
        val auth = AuthService(store, LoginRateLimiter(config, dataSource))
        val pop3 = Pop3Server(config, store, auth)

        try {
            auth.register("bob", "bob-test-pw")
            pop3.start()
            waitForPort(config.pop3Port)

            Socket(config.pop3Host, config.pop3Port).use { plainSocket ->
                plainSocket.soTimeout = 5_000
                var reader = BufferedReader(InputStreamReader(plainSocket.getInputStream(), Charsets.UTF_8))
                var writer = PrintWriter(plainSocket.getOutputStream(), true)
                assertTrue(reader.readLine().startsWith("+OK"))
                writer.println("CAPA")
                val capabilities = readUntilDot(reader)
                assertTrue(capabilities.contains("STLS"), "server must advertise STLS when TLS material is configured")

                writer.println("USER bob")
                assertTrue(reader.readLine().startsWith("-ERR"))
                writer.println("STLS")
                assertTrue(reader.readLine().startsWith("+OK"))

                val tlsSocket = trustAllClientContext().socketFactory.createSocket(
                    plainSocket,
                    config.pop3Host,
                    config.pop3Port,
                    false
                ) as SSLSocket
                tlsSocket.soTimeout = 5_000
                tlsSocket.useClientMode = true
                tlsSocket.startHandshake()
                reader = BufferedReader(InputStreamReader(tlsSocket.getInputStream(), Charsets.UTF_8))
                writer = PrintWriter(tlsSocket.getOutputStream(), true)

                writer.println("USER bob")
                assertTrue(reader.readLine().startsWith("+OK"))
                writer.println("PASS bob-test-pw")
                assertTrue(reader.readLine().startsWith("+OK"))
                writer.println("QUIT")
            }
        } finally {
            pop3.stop()
            dataSource.close()
        }
    }

    @Test
    fun smtpDeliversMessageAndPop3RetrievesItOverSockets() {
        val workDir = Files.createTempDirectory("smtp-pop3-it")
        val config = AppConfig(
            domain = "socket.local",
            h2Url = "jdbc:h2:${workDir.resolve("mail-db")};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
            attachmentDir = workDir.resolve("attachments").toString(),
            smtpHost = "127.0.0.1",
            smtpPort = freePort(),
            pop3Host = "127.0.0.1",
            pop3Port = freePort(),
            adminToken = "test-admin-token",
            adminSessionSecret = "valid-admin-session-secret-32-chars",
            socketTimeoutMillis = 5_000,
            maxMessageBytes = 4096,
            smtpRequireTlsForAuth = false,
            pop3RequireTlsForAuth = false
        )
        val dataSource = DataSourceFactory.hikari(config.h2Url, config.h2User, config.h2Password)
        val store = H2MailStore(config.domain, dataSource)
        val audit = AuditService(dataSource)
        val limiter = LoginRateLimiter(config, dataSource)
        val auth = AuthService(store, limiter)
        val mail = MailService(store, MimeParser(AttachmentStorage(config.attachmentDir)), audit)
        val worker = MailQueueWorker(store, config)
        val smtp = SmtpServer(config, mail, auth, "mail.${config.domain}", store)
        val pop3 = Pop3Server(config, store, auth)

        try {
            auth.register("alice", "alice-test-pw")
            auth.register("bob", "bob-test-pw")
            smtp.start()
            pop3.start()
            waitForPort(config.smtpPort)
            waitForPort(config.pop3Port)

            Socket(config.smtpHost, config.smtpPort).use { socket ->
                socket.soTimeout = 5_000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val writer = PrintWriter(socket.getOutputStream(), true)
                assertTrue(reader.readLine().startsWith("220"))
                writer.println("EHLO test")
                readUntil(reader, "250 ")
                val authPayload = Base64.getEncoder().encodeToString(
                    byteArrayOf(0) +
                        "alice".toByteArray(Charsets.UTF_8) +
                        byteArrayOf(0) +
                        "alice-test-pw".toByteArray(Charsets.UTF_8)
                )
                writer.println("AUTH PLAIN $authPayload")
                assertTrue(reader.readLine().startsWith("235"))
                writer.println("MAIL FROM:<alice@socket.local>")
                assertTrue(reader.readLine().startsWith("250"))
                writer.println("RCPT TO:<bob@socket.local>")
                assertTrue(reader.readLine().startsWith("250"))
                writer.println("DATA")
                assertTrue(reader.readLine().startsWith("354"))
                writer.println("Subject: Socket hello")
                writer.println("Content-Type: text/plain; charset=utf-8")
                writer.println()
                writer.println("hello over smtp socket")
                writer.println("..dot-stuffed line survives")
                writer.println(".")
                assertTrue(reader.readLine().startsWith("250"))
                writer.println("QUIT")
            }

            worker.drain()

            Socket(config.pop3Host, config.pop3Port).use { socket ->
                socket.soTimeout = 5_000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val writer = PrintWriter(socket.getOutputStream(), true)
                assertTrue(reader.readLine().startsWith("+OK"))
                writer.println("USER bob")
                assertTrue(reader.readLine().startsWith("+OK"))
                writer.println("PASS bob-test-pw")
                assertTrue(reader.readLine().startsWith("+OK"))
                writer.println("RETR 1")
                assertTrue(reader.readLine().startsWith("+OK"))
                val message = readUntilDot(reader)
                assertTrue(message.contains("Socket hello"))
                assertTrue(message.contains("hello over smtp socket"))
                assertTrue(message.contains(".dot-stuffed line survives"))
                writer.println("QUIT")
            }
        } finally {
            smtp.stop()
            pop3.stop()
            dataSource.close()
        }
    }

    @Test
    fun pop3SoftDeletesMessageOnQuitAndRetrMarksRead() {
        val workDir = Files.createTempDirectory("pop3-delete-read-it")
        val config = AppConfig(
            domain = "socket.local",
            h2Url = "jdbc:h2:${workDir.resolve("mail-db")};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
            attachmentDir = workDir.resolve("attachments").toString(),
            smtpHost = "127.0.0.1",
            smtpPort = freePort(),
            pop3Host = "127.0.0.1",
            pop3Port = freePort(),
            adminToken = "test-admin-token",
            adminSessionSecret = "valid-admin-session-secret-32-chars",
            socketTimeoutMillis = 5_000,
            smtpRequireTlsForAuth = false,
            pop3RequireTlsForAuth = false
        )
        val dataSource = DataSourceFactory.hikari(config.h2Url, config.h2User, config.h2Password)
        val store = H2MailStore(config.domain, dataSource)
        val auth = AuthService(store, LoginRateLimiter(config, dataSource))
        val pop3 = Pop3Server(config, store, auth)

        try {
            auth.register("alice", "alice-test-pw")
            auth.register("bob", "bob-test-pw")
            val message = store.saveMessage(com.maoning.mail.store.MailMessage(from = "alice@socket.local", to = listOf("bob@socket.local"), subject = "Delete me", body = "body"))
            store.queueRecipients(message.id, listOf("bob@socket.local"))
            MailQueueWorker(store, config).drain()

            pop3.start()
            waitForPort(config.pop3Port)

            Socket(config.pop3Host, config.pop3Port).use { socket ->
                socket.soTimeout = 5_000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val writer = PrintWriter(socket.getOutputStream(), true)
                assertTrue(reader.readLine().startsWith("+OK"))
                writer.println("USER bob")
                assertTrue(reader.readLine().startsWith("+OK"))
                writer.println("PASS bob-test-pw")
                assertTrue(reader.readLine().startsWith("+OK"))
                writer.println("RETR 1")
                assertTrue(reader.readLine().startsWith("+OK"))
                assertTrue(readUntilDot(reader).contains("Delete me"))
                assertTrue(store.findMessageForMailbox(message.id, "bob@socket.local")!!.read)
                writer.println("DELE 1")
                assertTrue(reader.readLine().startsWith("+OK message 1 marked for deletion"))
                assertTrue(store.findMessageForMailbox(message.id, "bob@socket.local") != null, "DELE is deferred until QUIT")
                writer.println("QUIT")
                assertTrue(reader.readLine().startsWith("+OK bye"))
            }

            assertTrue(store.findMessageForMailbox(message.id, "bob@socket.local") == null, "deleted messages are hidden from normal mailbox reads")
            val state = store.mailboxState("bob@socket.local", message.id, includeDeleted = true)
            assertTrue(state?.deleted == true, "POP3 DELE soft-deletes mailbox row instead of permanent purge")
            assertTrue(state!!.read, "RETR read mark is retained after deferred delete")
        } finally {
            pop3.stop()
            dataSource.close()
        }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun waitForPort(port: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            runCatching { Socket("127.0.0.1", port).close(); return }
            Thread.sleep(50)
        }
        error("Port $port did not open")
    }

    private fun readUntil(reader: BufferedReader, terminalPrefix: String) {
        while (true) {
            val line = reader.readLine() ?: error("Connection closed")
            if (line.startsWith(terminalPrefix)) return
        }
    }

    private fun readEhloCapabilities(reader: BufferedReader): List<String> = buildList {
        while (true) {
            val line = reader.readLine() ?: error("Connection closed")
            add(line)
            if (line.startsWith("250 ")) return@buildList
        }
    }

    private fun readUntilDot(reader: BufferedReader): String = buildString {
        while (true) {
            val line = reader.readLine() ?: break
            if (line == ".") break
            appendLine(line)
        }
    }

    private fun createSelfSignedKeyStore(path: String, password: String) {
        val process = ProcessBuilder(
            "keytool",
            "-genkeypair",
            "-alias",
            "mail-test",
            "-keyalg",
            "RSA",
            "-keysize",
            "2048",
            "-validity",
            "1",
            "-storetype",
            "PKCS12",
            "-keystore",
            path,
            "-storepass",
            password,
            "-keypass",
            password,
            "-dname",
            "CN=localhost"
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "keytool failed: $output" }
    }

    private fun trustAllClientContext(): SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }), null)
    }
}
