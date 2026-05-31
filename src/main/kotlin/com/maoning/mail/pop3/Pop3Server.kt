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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class Pop3Server(
    private val config: AppConfig,
    private val store: MailStore,
    private val authService: AuthService
) {
    private val logger = LoggerFactory.getLogger(Pop3Server::class.java)
    private val running = AtomicBoolean(false)
    @Volatile private var serverSocket: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(config.pop3MaxConnections + 1)

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
        socket.use {
            val reader = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.UTF_8))
            val writer = PrintWriter(it.getOutputStream(), true)
            var username: String? = null
            var mailbox: String? = null
            var messages: List<MailMessage> = emptyList()
            writer.ok("Kotlin POP3 ready")
            while (true) {
                val line = reader.readLine() ?: break
                val command = line.substringBefore(' ').uppercase()
                val arg = line.substringAfter(' ', "").trim()
                when (command) {
                    "USER" -> { username = arg; writer.ok("user accepted") }
                    "PASS" -> {
                        val user = username
                        if (user == null) writer.err("USER required first") else runCatching {
                            mailbox = authService.login(user, arg, socket.inetAddress.hostAddress).mailbox
                            messages = store.inbox(mailbox!!)
                            writer.ok("maildrop has ${messages.size} messages")
                        }.onFailure { writer.err("authentication failed") }
                    }
                    "STAT" -> ifAuthenticated(writer, mailbox) { writer.ok("${messages.size} ${messages.sumOf { it.toRfc822().toByteArray().size }}") }
                    "LIST" -> ifAuthenticated(writer, mailbox) {
                        if (arg.isBlank()) {
                            writer.ok("scan listing follows")
                            messages.forEachIndexed { index, msg -> writer.println("${index + 1} ${msg.toRfc822().toByteArray().size}") }
                            writer.println(".")
                        } else {
                            val msg = messages.getOrNull(arg.toIntOrNull()?.minus(1) ?: -1) ?: return@ifAuthenticated writer.err("no such message")
                            writer.ok("$arg ${msg.toRfc822().toByteArray().size}")
                        }
                    }
                    "RETR" -> ifAuthenticated(writer, mailbox) {
                        val msg = messages.getOrNull(arg.toIntOrNull()?.minus(1) ?: -1) ?: return@ifAuthenticated writer.err("no such message")
                        writer.ok("message follows")
                        msg.toRfc822().lineSequence().forEach { bodyLine -> writer.println(if (bodyLine.startsWith('.')) ".$bodyLine" else bodyLine) }
                        writer.println(".")
                    }
                    "NOOP" -> writer.ok("OK")
                    "RSET" -> writer.ok("OK")
                    "DELE" -> writer.err("delete is not implemented; use web/admin retention policy")
                    "QUIT" -> { writer.ok("bye"); break }
                    else -> writer.err("unknown command")
                }
            }
        }
    }

    private inline fun ifAuthenticated(writer: PrintWriter, mailbox: String?, block: () -> Unit) {
        if (mailbox == null) writer.err("authentication required") else block()
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
