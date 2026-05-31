package com.maoning.mail

import com.maoning.mail.api.mailRoutes
import com.maoning.mail.attachment.AttachmentStorage
import com.maoning.mail.audit.AuditService
import com.maoning.mail.auth.AuthService
import com.maoning.mail.config.AppConfig
import com.maoning.mail.db.DataSourceFactory
import com.maoning.mail.db.H2MailStore
import com.maoning.mail.db.closeIfPossible
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
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import kotlinx.serialization.json.Json

fun main() {
    val config = AppConfig()
    config.validateForRuntime()
    val dataSource = DataSourceFactory.hikari(config.h2Url, config.h2User, config.h2Password)
    val store = H2MailStore(config.domain, dataSource)
    val auditService = AuditService(dataSource)
    val loginRateLimiter = LoginRateLimiter(config, dataSource)
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
        dataSource.closeIfPossible()
    })

    embeddedServer(Netty, host = config.httpHost, port = config.httpPort) {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                ignoreUnknownKeys = true
            })
        }
        install(Sessions) {
            cookie<AdminSession>("ADMIN_SESSION") {
                cookie.path = "/admin"
                cookie.httpOnly = true
                cookie.secure = config.secureCookies
                cookie.extensions["SameSite"] = "Lax"
            }
            cookie<WebmailSession>("WEBMAIL_SESSION") {
                cookie.path = "/webmail"
                cookie.httpOnly = true
                cookie.secure = config.secureCookies
                cookie.extensions["SameSite"] = "Lax"
            }
        }
        mailRoutes(config, authService, mailService, store, queueWorker, auditService, loginRateLimiter, attachmentStorage)
        webmailRoutes(config, authService, mailService, store, attachmentStorage, auditService)
    }.start(wait = true)
}

data class AdminSession(val token: String)
data class WebmailSession(val token: String)
