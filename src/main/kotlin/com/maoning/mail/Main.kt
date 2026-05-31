package com.maoning.mail

import com.maoning.mail.api.mailRoutes
import com.maoning.mail.attachment.AttachmentStorage
import com.maoning.mail.audit.AuditService
import com.maoning.mail.auth.AuthService
import com.maoning.mail.config.AppConfig
import com.maoning.mail.db.H2MailStore
import com.maoning.mail.mail.MailService
import com.maoning.mail.mime.MimeParser
import com.maoning.mail.pop3.Pop3Server
import com.maoning.mail.queue.MailQueueWorker
import com.maoning.mail.security.LoginRateLimiter
import com.maoning.mail.smtp.SmtpServer
import com.maoning.mail.webmail.webmailRoutes
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import kotlinx.serialization.json.Json

fun main() {
    val config = AppConfig()
    config.validateForRuntime()
    val store = H2MailStore(config.domain, config.h2Url, config.h2User, config.h2Password)
    val auditService = AuditService(config.h2Url, config.h2User, config.h2Password)
    val loginRateLimiter = LoginRateLimiter(config, config.h2Url, config.h2User, config.h2Password)
    val authService = AuthService(store, loginRateLimiter)
    val attachmentStorage = AttachmentStorage(config.attachmentDir)
    val mimeParser = MimeParser(attachmentStorage, config.maxAttachmentBytes, config.maxTotalAttachmentBytes)
    val mailService = MailService(store, mimeParser, auditService)
    val queueWorker = MailQueueWorker(store, config)
    val smtpServer = SmtpServer(config, mailService, authService, "mail.${config.domain}", store)
    val pop3Server = Pop3Server(config, store, authService)

    queueWorker.start()
    smtpServer.start()
    pop3Server.start()

    Runtime.getRuntime().addShutdownHook(Thread {
        smtpServer.stop()
        pop3Server.stop()
        queueWorker.stop()
        store.close()
    })

    embeddedServer(Netty, host = config.httpHost, port = config.httpPort) {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                ignoreUnknownKeys = true
            })
        }
        mailRoutes(config, authService, mailService, store, queueWorker, auditService, loginRateLimiter, attachmentStorage)
        webmailRoutes(config, authService, mailService, store, attachmentStorage, auditService)
    }.start(wait = true)
}
