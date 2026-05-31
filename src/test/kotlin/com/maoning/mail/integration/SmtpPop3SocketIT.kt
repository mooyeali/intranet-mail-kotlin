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
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertTrue

class SmtpPop3SocketIT {
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
            maxMessageBytes = 4096
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
            auth.register("alice", "alicePass123")
            auth.register("bob", "bobPass123")
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
                val authPayload = Base64.getEncoder().encodeToString("\u0000alice\u0000alicePass123".toByteArray(Charsets.UTF_8))
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
                writer.println("PASS bobPass123")
                assertTrue(reader.readLine().startsWith("+OK"))
                writer.println("RETR 1")
                assertTrue(reader.readLine().startsWith("+OK"))
                val message = readUntilDot(reader)
                assertTrue(message.contains("Socket hello"))
                assertTrue(message.contains("hello over smtp socket"))
                writer.println("QUIT")
            }
        } finally {
            smtp.stop()
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

    private fun readUntilDot(reader: BufferedReader): String = buildString {
        while (true) {
            val line = reader.readLine() ?: break
            if (line == ".") break
            appendLine(line)
        }
    }
}
