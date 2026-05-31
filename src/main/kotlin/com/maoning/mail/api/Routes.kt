package com.maoning.mail.api

import com.maoning.mail.attachment.AttachmentStorage
import com.maoning.mail.audit.AuditService
import com.maoning.mail.auth.AuthService
import com.maoning.mail.config.AppConfig
import com.maoning.mail.mail.MailService
import com.maoning.mail.queue.MailQueueWorker
import com.maoning.mail.security.LoginRateLimiter
import com.maoning.mail.store.MailStore
import com.maoning.mail.store.QueueStatus
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.html.*
import kotlinx.serialization.Serializable
import org.mindrot.jbcrypt.BCrypt

private const val ADMIN_SESSION_COOKIE = "ADMIN_SESSION"

@Serializable data class RegisterRequest(val username: String, val password: String)
@Serializable data class LoginRequest(val username: String, val password: String)
@Serializable data class SendMailRequest(val to: List<String>, val subject: String, val body: String)
@Serializable data class UserResponse(val id: String, val username: String, val mailbox: String)
@Serializable data class LoginResponse(val token: String, val mailbox: String, val expiresAt: Long)
@Serializable data class ErrorResponse(val error: String)

fun Application.mailRoutes(
    config: AppConfig,
    authService: AuthService,
    mailService: MailService,
    store: MailStore,
    queueWorker: MailQueueWorker,
    auditService: AuditService,
    @Suppress("UNUSED_PARAMETER") loginRateLimiter: LoginRateLimiter,
    attachmentStorage: AttachmentStorage
) {
    routing {
        get("/health") { call.respond(mapOf("status" to "ok", "domain" to config.domain)) }

        post("/api/register") {
            runCatching {
                val req = call.receive<RegisterRequest>()
                val user = authService.register(req.username, req.password)
                auditService.record(user.mailbox, "USER_REGISTER", user.mailbox, ip = call.clientIp())
                UserResponse(user.id, user.username, user.mailbox)
            }.fold(
                onSuccess = { call.respond(HttpStatusCode.Created, it) },
                onFailure = { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "register failed")) }
            )
        }

        post("/api/login") {
            val req = call.receive<LoginRequest>()
            val ip = call.clientIp()
            runCatching {
                val session = authService.login(req.username, req.password, ip)
                auditService.record(session.mailbox, "USER_LOGIN", session.mailbox, ip = ip)
                LoginResponse(session.token, session.mailbox, session.expiresAt)
            }.fold(
                onSuccess = { call.respond(it) },
                onFailure = {
                    auditService.record(req.username, "USER_LOGIN_FAILED", req.username, it.message, ip)
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse(it.message ?: "invalid credentials"))
                }
            )
        }

        post("/api/mail/send") {
            val session = authService.authenticate(call.request.headers["Authorization"])
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("missing or invalid token"))
            runCatching {
                val req = call.receive<SendMailRequest>()
                mailService.send(session.mailbox, req.to, req.subject, req.body)
            }.fold(
                onSuccess = { call.respond(HttpStatusCode.Created, it) },
                onFailure = { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "send failed")) }
            )
        }

        get("/api/mail/inbox") {
            val session = authService.authenticate(call.request.headers["Authorization"])
                ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("missing or invalid token"))
            call.respond(mailService.inbox(session.mailbox))
        }

        get("/api/mail/sent") {
            val session = authService.authenticate(call.request.headers["Authorization"])
                ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("missing or invalid token"))
            call.respond(mailService.sent(session.mailbox))
        }

        get("/api/mail/search") {
            val session = authService.authenticate(call.request.headers["Authorization"])
                ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("missing or invalid token"))
            val query = call.request.queryParameters["q"].orEmpty()
            val box = call.request.queryParameters["box"]?.takeIf { it == "inbox" || it == "sent" }
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
            call.respond(store.search(session.mailbox, query, box, limit))
        }

        get("/api/mail/{messageId}/attachments/{attachmentId}") {
            val session = authService.authenticate(call.request.headers["Authorization"])
                ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("missing or invalid token"))
            val messageId = call.parameters["messageId"].orEmpty()
            val attachmentId = call.parameters["attachmentId"].orEmpty()
            val message = store.findMessageForMailbox(messageId, session.mailbox)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("message not found"))
            val attachment = message.attachments.firstOrNull { it.id == attachmentId }
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("attachment not found"))
            val path = attachment.path ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("attachment has no file"))
            auditService.record(session.mailbox, "ATTACHMENT_DOWNLOAD", messageId, attachment.fileName, call.clientIp())
            call.response.headers.append("Content-Disposition", "attachment; filename=\"${attachment.fileName.replace("\"", "_")}\"")
            call.respondFile(attachmentStorage.resolve(path).toFile())
        }

        get("/admin/login") {
            call.respondHtml {
                body {
                    h1 { +"Intranet Mail Admin Login" }
                    form(action = "/admin/login", method = FormMethod.post) {
                        p { +"Username"; br; textInput(name = "username") }
                        p { +"Password"; br; passwordInput(name = "password") }
                        submitInput { value = "Login" }
                    }
                }
            }
        }

        post("/admin/login") {
            val params = call.receiveParameters()
            val username = params["username"].orEmpty()
            val password = params["password"].orEmpty()
            val ip = call.clientIp()
            if (username == config.adminUser && config.adminPasswordHash.isNotBlank() && BCrypt.checkpw(password, config.adminPasswordHash)) {
                call.response.cookies.append(adminCookie(config, config.adminToken))
                auditService.record(username, "ADMIN_LOGIN", ip = ip)
                call.respondRedirect("/admin")
            } else {
                auditService.record(username, "ADMIN_LOGIN_FAILED", ip = ip)
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid admin credentials"))
            }
        }

        get("/admin/logout") {
            call.response.cookies.append(adminCookie(config, "", maxAge = 0))
            call.respondRedirect("/admin/login")
        }

        get("/admin") {
            if (!call.isAdmin(config)) return@get call.respondRedirect("/admin/login")
            val users = store.users()
            val messages = store.messages(50)
            val queue = store.queueItems()
            val audits = auditService.recent(50)
            call.respondHtml {
                head { title("Intranet Mail Admin") }
                body {
                    h1 { +"Intranet Mail Admin" }
                    p { a("/admin/logout") { +"Logout" } }
                    p { +"domain=${config.domain}, users=${users.size}, messages=${messages.size}, queue=${queue.size}" }
                    h2 { +"Users" }
                    table { attributes["border"] = "1"; tr { th { +"mailbox" }; th { +"createdAt" } }; users.forEach { tr { td { +it.mailbox }; td { +it.createdAt.toString() } } } }
                    h2 { +"Queue / Dead letters" }
                    table { attributes["border"] = "1"; tr { th { +"recipient" }; th { +"status" }; th { +"attempts" }; th { +"error" } }; queue.forEach { tr { td { +it.recipient }; td { +it.status.name }; td { +it.attempts.toString() }; td { +(it.lastError ?: "") } } } }
                    h2 { +"Recent messages" }
                    table { attributes["border"] = "1"; tr { th { +"from" }; th { +"to" }; th { +"subject" }; th { +"attachments" } }; messages.forEach { tr { td { +it.from }; td { +it.to.joinToString() }; td { +it.subject }; td { +it.attachments.size.toString() } } } }
                    h2 { +"Audit" }
                    table { attributes["border"] = "1"; tr { th { +"actor" }; th { +"action" }; th { +"target" }; th { +"ip" }; th { +"time" } }; audits.forEach { tr { td { +(it.actor ?: "") }; td { +it.action }; td { +(it.target ?: "") }; td { +(it.ip ?: "") }; td { +it.createdAt.toString() } } } }
                }
            }
        }

        get("/admin/api/users") { call.adminOr401(config) { call.respond(store.users()) } }
        get("/admin/api/messages") { call.adminOr401(config) { call.respond(store.messages(200)) } }
        get("/admin/api/queue") { call.adminOr401(config) { call.respond(store.queueItems()) } }
        get("/admin/api/dead") { call.adminOr401(config) { call.respond(store.queueItems(QueueStatus.DEAD)) } }
        get("/admin/api/audit") { call.adminOr401(config) { call.respond(auditService.recent(200)) } }
        post("/admin/api/queue/drain") { call.adminOr401(config) { queueWorker.drain(); call.respond(mapOf("status" to "ok")) } }

        get("/dns") {
            call.respondText(
                """
                # 内网 DNS 示例 zone 记录
                ${config.domain}.        IN MX 10 mail.${config.domain}.
                mail.${config.domain}.   IN A  192.168.1.10
                smtp.${config.domain}.   IN CNAME mail.${config.domain}.
                pop3.${config.domain}.   IN CNAME mail.${config.domain}.
                _smtp._tcp.${config.domain}. IN SRV 0 5 ${config.smtpPort} mail.${config.domain}.
                _pop3._tcp.${config.domain}. IN SRV 0 5 ${config.pop3Port} mail.${config.domain}.
                """.trimIndent(), ContentType.Text.Plain
            )
        }
    }
}

private suspend fun ApplicationCall.adminOr401(config: AppConfig, block: suspend () -> Unit) {
    if (!isAdmin(config)) respond(HttpStatusCode.Unauthorized, ErrorResponse("admin token required")) else block()
}

private fun ApplicationCall.isAdmin(config: AppConfig): Boolean =
    request.cookies[ADMIN_SESSION_COOKIE] == config.adminToken ||
        request.headers["X-Admin-Token"] == config.adminToken ||
        (config.adminQueryTokenEnabled && request.queryParameters["token"] == config.adminToken)

private fun adminCookie(config: AppConfig, value: String, maxAge: Int = 24 * 3600): Cookie = Cookie(
    name = ADMIN_SESSION_COOKIE,
    value = value,
    path = "/admin",
    maxAge = maxAge,
    httpOnly = true,
    secure = config.secureCookies,
    extensions = mapOf("SameSite" to "Lax")
)

private fun ApplicationCall.clientIp(): String = request.headers["X-Forwarded-For"]?.substringBefore(',')?.trim()
    ?: request.local.remoteHost
