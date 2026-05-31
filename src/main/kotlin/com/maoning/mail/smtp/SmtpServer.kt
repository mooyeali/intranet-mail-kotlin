package com.maoning.mail.smtp

import com.maoning.mail.auth.AuthService
import com.maoning.mail.config.AppConfig
import com.maoning.mail.mail.MailService
import com.maoning.mail.store.MailStore
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

class SmtpServer(
    private val config: AppConfig,
    private val mailService: MailService,
    private val authService: AuthService,
    private val serverName: String,
    private val store: MailStore
) {
    private val logger = LoggerFactory.getLogger(SmtpServer::class.java)
    private val running = AtomicBoolean(false)
    private val pool = Executors.newFixedThreadPool(config.smtpMaxConnections + 1)
    private val sslContext: SSLContext? = createSslContext()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        pool.submit {
            ServerSocket().use { server ->
                server.reuseAddress = true
                server.bind(InetSocketAddress(config.smtpHost, config.smtpPort), config.smtpMaxConnections)
                logger.info("SMTP server listening on {}:{}", config.smtpHost, config.smtpPort)
                while (running.get()) {
                    val socket = server.accept()
                    socket.soTimeout = config.socketTimeoutMillis
                    pool.submit { handleSafely(socket) }
                }
            }
        }
    }

    fun stop() {
        running.set(false)
        pool.shutdownNow()
    }

    private fun handleSafely(socket: Socket) {
        runCatching { handle(socket) }
            .onFailure { if (it !is SocketTimeoutException) logger.warn("SMTP session failed", it) }
    }

    private fun handle(socket: Socket) {
        var activeSocket = socket
        var authenticatedMailbox: String? = null
        var from: String? = null
        val recipients = mutableListOf<String>()

        fun streams(): Pair<BufferedReader, PrintWriter> =
            BufferedReader(InputStreamReader(activeSocket.getInputStream(), Charsets.UTF_8)) to PrintWriter(activeSocket.getOutputStream(), true)

        var (reader, writer) = streams()
        activeSocket.use {
            writer.reply(220, "$serverName Kotlin SMTP ready")
            while (true) {
                val line = reader.readLine() ?: break
                val upper = line.uppercase()
                when {
                    upper.startsWith("HELO") -> writer.reply(250, serverName)
                    upper.startsWith("EHLO") -> {
                        writer.println("250-$serverName")
                        writer.println("250-AUTH PLAIN LOGIN")
                        if (sslContext != null) writer.println("250-STARTTLS")
                        writer.println("250 SIZE ${config.maxMessageBytes}")
                    }
                    upper == "STARTTLS" -> {
                        if (sslContext == null) {
                            writer.reply(454, "TLS not available")
                        } else {
                            writer.reply(220, "Ready to start TLS")
                            val ssl = sslContext.socketFactory.createSocket(activeSocket, activeSocket.inetAddress.hostAddress, activeSocket.port, true) as SSLSocket
                            ssl.soTimeout = config.socketTimeoutMillis
                            ssl.useClientMode = false
                            ssl.startHandshake()
                            activeSocket = ssl
                            val newStreams = streams()
                            reader = newStreams.first
                            writer = newStreams.second
                        }
                    }
                    upper.startsWith("AUTH PLAIN") -> {
                        val payload = line.substringAfter("AUTH PLAIN", "").trim().ifBlank {
                            writer.print("334 "); writer.println(); reader.readLine()
                        }
                        authenticatedMailbox = authPlain(payload, writer, activeSocket.inetAddress.hostAddress)
                    }
                    upper.startsWith("AUTH LOGIN") -> {
                        authenticatedMailbox = authLogin(reader, writer, activeSocket.inetAddress.hostAddress)
                    }
                    upper == "NOOP" -> writer.reply(250, "OK")
                    upper == "RSET" -> {
                        from = null
                        recipients.clear()
                        writer.reply(250, "OK")
                    }
                    upper.startsWith("MAIL FROM:") -> {
                        if (authenticatedMailbox == null) {
                            writer.reply(530, "Authentication required")
                            continue
                        }
                        val sender = extractAddress(line)
                        if (!sender.equals(authenticatedMailbox, ignoreCase = true)) {
                            writer.reply(553, "Authenticated user may only send as $authenticatedMailbox")
                            continue
                        }
                        from = sender
                        writer.reply(250, "Sender OK")
                    }
                    upper.startsWith("RCPT TO:") -> {
                        val rcpt = store.normalizeMailbox(extractAddress(line))
                        if (!store.userExists(rcpt)) {
                            writer.reply(550, "No such user: $rcpt")
                            continue
                        }
                        recipients += rcpt
                        writer.reply(250, "Recipient OK")
                    }
                    upper == "DATA" -> {
                        if (from == null || recipients.isEmpty()) {
                            writer.reply(503, "Need MAIL FROM and RCPT TO first")
                            continue
                        }
                        writer.reply(354, "End data with <CR><LF>.<CR><LF>")
                        val raw = readData(reader, config.maxMessageBytes)
                        if (raw == null) {
                            writer.reply(552, "Message size exceeds fixed limit of ${config.maxMessageBytes} bytes")
                            continue
                        }
                        runCatching { mailService.receiveSmtp(from!!, recipients.toList(), raw) }
                            .onSuccess { writer.reply(250, "Queued as ${it.id}") }
                            .onFailure { writer.reply(550, it.message ?: "Delivery failed") }
                    }
                    upper == "QUIT" -> {
                        writer.reply(221, "Bye")
                        break
                    }
                    else -> writer.reply(502, "Command not implemented")
                }
            }
        }
    }

    private fun readData(reader: BufferedReader, maxBytes: Long): String? {
        val builder = StringBuilder()
        var bytes = 0L
        while (true) {
            val dataLine = reader.readLine() ?: break
            if (dataLine == ".") break
            val normalized = if (dataLine.startsWith("..")) dataLine.drop(1) else dataLine
            bytes += normalized.toByteArray(Charsets.UTF_8).size + 2L
            if (bytes > maxBytes) return null
            builder.appendLine(normalized)
        }
        return builder.toString()
    }

    private fun authPlain(payload: String, writer: PrintWriter, ip: String): String? = runCatching {
        val decoded = String(Base64.getDecoder().decode(payload), Charsets.UTF_8)
        val parts = decoded.split('\u0000')
        val username = parts.getOrNull(parts.size - 2).orEmpty()
        val password = parts.last()
        authService.login(username, password, ip).mailbox.also { writer.reply(235, "Authentication successful") }
    }.getOrElse { writer.reply(535, "Authentication failed"); null }

    private fun authLogin(reader: BufferedReader, writer: PrintWriter, ip: String): String? = runCatching {
        writer.println("334 VXNlcm5hbWU6")
        val username = String(Base64.getDecoder().decode(reader.readLine()), Charsets.UTF_8)
        writer.println("334 UGFzc3dvcmQ6")
        val password = String(Base64.getDecoder().decode(reader.readLine()), Charsets.UTF_8)
        authService.login(username, password, ip).mailbox.also { writer.reply(235, "Authentication successful") }
    }.getOrElse { writer.reply(535, "Authentication failed"); null }

    private fun extractAddress(line: String): String = line.substringAfter(':').trim().removePrefix("<").substringBefore('>').trim()

    private fun createSslContext(): SSLContext? {
        val path = config.tlsKeyStore ?: return null
        val pass = config.tlsKeyStorePassword ?: return null
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        java.io.FileInputStream(path).use { ks.load(it, pass.toCharArray()) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, pass.toCharArray())
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    }

    private fun PrintWriter.reply(code: Int, text: String) = println("$code $text")
}
