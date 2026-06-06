package com.maoning.mail.pop3

import com.maoning.mail.auth.AuthService
import com.maoning.mail.config.AppConfig
import com.maoning.mail.store.MailMessage
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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

class Pop3Server(
    private val config: AppConfig,
    private val store: MailStore,
    private val authService: AuthService
) {
    private val logger = LoggerFactory.getLogger(Pop3Server::class.java)
    private val running = AtomicBoolean(false)
    @Volatile private var serverSocket: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(config.pop3MaxConnections + 1)
    private val sslContext: SSLContext? = createSslContext()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        pool.submit {
            ServerSocket().use { server ->
                serverSocket = server
                server.reuseAddress = true
                server.bind(InetSocketAddress(config.pop3Host, config.pop3Port), config.pop3MaxConnections)
                logger.info("POP3 server listening on {}:{}", config.pop3Host, config.pop3Port)
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
        runCatching { serverSocket?.close() }
        pool.shutdownNow()
    }

    private fun handleSafely(socket: Socket) {
        runCatching { handle(socket) }
            .onFailure { if (it !is SocketTimeoutException) logger.warn("POP3 session failed", it) }
    }

    private fun handle(socket: Socket) {
        var activeSocket = socket
        var reader = BufferedReader(InputStreamReader(activeSocket.getInputStream(), Charsets.UTF_8))
        var writer = PrintWriter(activeSocket.getOutputStream(), true)
        var username: String? = null
        var mailbox: String? = null
        var tlsActive = false
        var messages: List<MailMessage> = emptyList()
        val deletedMessageIds = linkedSetOf<String>()

        fun tlsRequired() = config.pop3RequireTlsForAuth && !tlsActive
        fun messageAt(arg: String): MailMessage? {
            val index = arg.toIntOrNull()?.minus(1) ?: return null
            return messages.getOrNull(index)?.takeIf { it.id !in deletedMessageIds }
        }
        activeSocket.use {
            writer.ok("Kotlin POP3 ready")
            while (true) {
                val line = reader.readLine() ?: break
                val command = line.substringBefore(' ').uppercase()
                val arg = line.substringAfter(' ', "").trim()
                when (command) {
                    "CAPA" -> {
                        writer.ok("Capability list follows")
                        writer.println("USER")
                        writer.println("UIDL")
                        if (sslContext != null && !tlsActive) writer.println("STLS")
                        writer.println(".")
                    }
                    "STLS" -> {
                        val context = sslContext
                        if (context == null) {
                            writer.err("TLS not available")
                        } else if (tlsActive) {
                            writer.err("TLS already active")
                        } else {
                            writer.ok("Begin TLS negotiation")
                            val ssl = context.socketFactory.createSocket(activeSocket, activeSocket.inetAddress.hostAddress, activeSocket.port, false) as SSLSocket
                            ssl.soTimeout = config.socketTimeoutMillis
                            ssl.useClientMode = false
                            ssl.startHandshake()
                            activeSocket = ssl
                            tlsActive = true
                            reader = BufferedReader(InputStreamReader(activeSocket.getInputStream(), Charsets.UTF_8))
                            writer = PrintWriter(activeSocket.getOutputStream(), true)
                        }
                    }
                    "USER" -> {
                        if (tlsRequired()) writer.err("TLS required before authentication") else { username = arg; writer.ok("user accepted") }
                    }
                    "PASS" -> {
                        if (tlsRequired()) {
                            writer.err("TLS required before authentication")
                            continue
                        }
                        val user = username
                        if (user == null) writer.err("USER required first") else runCatching {
                            mailbox = authService.login(user, arg, activeSocket.inetAddress.hostAddress).mailbox
                            messages = store.inbox(mailbox!!)
                            deletedMessageIds.clear()
                            writer.ok("maildrop has ${messages.size} messages")
                        }.onFailure { writer.err("authentication failed") }
                    }
                    "STAT" -> ifAuthenticated(writer, mailbox) {
                        val activeMessages = messages.filter { it.id !in deletedMessageIds }
                        writer.ok("${activeMessages.size} ${activeMessages.sumOf { it.toRfc822().toByteArray().size }}")
                    }
                    "LIST" -> ifAuthenticated(writer, mailbox) {
                        if (arg.isBlank()) {
                            writer.ok("scan listing follows")
                            messages.forEachIndexed { index, msg -> if (msg.id !in deletedMessageIds) writer.println("${index + 1} ${msg.toRfc822().toByteArray().size}") }
                            writer.println(".")
                        } else {
                            val index = arg.toIntOrNull()?.minus(1) ?: -1
                            val msg = messages.getOrNull(index)?.takeIf { it.id !in deletedMessageIds } ?: return@ifAuthenticated writer.err("no such message")
                            writer.ok("$arg ${msg.toRfc822().toByteArray().size}")
                        }
                    }
                    "RETR" -> ifAuthenticated(writer, mailbox) {
                        val msg = messageAt(arg) ?: return@ifAuthenticated writer.err("no such message")
                        store.markRead(mailbox!!, msg.id, true)
                        writer.ok("message follows")
                        msg.toRfc822().lineSequence().forEach { bodyLine -> writer.println(if (bodyLine.startsWith('.')) ".$bodyLine" else bodyLine) }
                        writer.println(".")
                    }
                    "NOOP" -> writer.ok("OK")
                    "RSET" -> { deletedMessageIds.clear(); writer.ok("OK") }
                    "DELE" -> ifAuthenticated(writer, mailbox) {
                        val index = arg.toIntOrNull()?.minus(1) ?: -1
                        val msg = messages.getOrNull(index)?.takeIf { it.id !in deletedMessageIds } ?: return@ifAuthenticated writer.err("no such message")
                        deletedMessageIds += msg.id
                        writer.ok("message ${index + 1} marked for deletion")
                    }
                    "QUIT" -> {
                        mailbox?.let { owner -> deletedMessageIds.forEach { messageId -> store.setDeleted(owner, messageId, true) } }
                        writer.ok("bye")
                        break
                    }
                    else -> writer.err("unknown command")
                }
            }
        }
    }

    private inline fun ifAuthenticated(writer: PrintWriter, mailbox: String?, block: () -> Unit) {
        if (mailbox == null) writer.err("authentication required") else block()
    }

    private fun createSslContext(): SSLContext? {
        val path = config.tlsKeyStore ?: return null
        val pass = config.tlsKeyStorePassword ?: return null
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        java.io.FileInputStream(path).use { ks.load(it, pass.toCharArray()) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, pass.toCharArray())
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    }

    private fun MailMessage.toRfc822(): String = raw ?: buildString {
        appendLine("From: $from")
        appendLine("To: ${to.joinToString()}")
        appendLine("Subject: $subject")
        appendLine("Content-Type: text/plain; charset=utf-8")
        appendLine()
        appendLine(body)
    }

    private fun PrintWriter.ok(text: String) = println("+OK $text")
    private fun PrintWriter.err(text: String) = println("-ERR $text")
}
