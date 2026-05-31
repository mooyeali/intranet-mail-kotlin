package com.maoning.mail.webmail

import com.maoning.mail.WebmailSession
import com.maoning.mail.attachment.AttachmentStorage
import com.maoning.mail.audit.AuditService
import com.maoning.mail.auth.AuthService
import com.maoning.mail.config.AppConfig
import com.maoning.mail.mail.MailService
import com.maoning.mail.store.MailMessage
import com.maoning.mail.store.MailStore
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import kotlinx.html.*
import java.security.SecureRandom
import java.util.Base64

private const val CSRF_COOKIE = "WEBMAIL_CSRF"

fun Application.webmailRoutes(
    config: AppConfig,
    authService: AuthService,
    mailService: MailService,
    store: MailStore,
    attachmentStorage: AttachmentStorage,
    auditService: AuditService
) {
    routing {
        get("/") { call.respondRedirect("/webmail/login") }

        get("/webmail/login") {
            val csrf = call.ensureCsrfCookie(config)
            call.respondHtml {
                head { title("Webmail Login") }
                body {
                    h1 { +"Intranet Webmail" }
                    form(action = "/webmail/login", method = FormMethod.post) {
                        csrfInput(csrf)
                        p { +"Mailbox"; br; textInput(name = "username") }
                        p { +"Password"; br; passwordInput(name = "password") }
                        submitInput { value = "Login" }
                    }
                    p { a("/webmail/register") { +"Register a mailbox" } }
                }
            }
        }

        post("/webmail/login") {
            val params = call.receiveParameters()
            if (!call.validCsrf(params["csrf"].orEmpty())) return@post call.respondText("CSRF validation failed", status = HttpStatusCode.Forbidden)
            runCatching { authService.login(params["username"].orEmpty(), params["password"].orEmpty(), call.clientIp()) }
                .onSuccess {
                    call.sessions.set(WebmailSession(it.token))
                    call.ensureCsrfCookie(config, force = true)
                    call.respondRedirect("/webmail/inbox")
                }
                .onFailure { call.respondText("Invalid credentials", status = HttpStatusCode.Unauthorized) }
        }

        get("/webmail/logout") {
            call.sessions.clear<WebmailSession>()
            call.response.cookies.append(webmailCookie(config, CSRF_COOKIE, "", maxAge = 0, httpOnly = false))
            call.respondRedirect("/webmail/login")
        }

        get("/webmail/register") {
            val csrf = call.ensureCsrfCookie(config)
            call.respondHtml {
                head { title("Register Mailbox") }
                body {
                    h1 { +"Register mailbox" }
                    form(action = "/webmail/register", method = FormMethod.post) {
                        csrfInput(csrf)
                        p { +"Username"; br; textInput(name = "username") }
                        p { +"Password"; br; passwordInput(name = "password") }
                        submitInput { value = "Register" }
                    }
                }
            }
        }

        post("/webmail/register") {
            val params = call.receiveParameters()
            if (!call.validCsrf(params["csrf"].orEmpty())) return@post call.respondText("CSRF validation failed", status = HttpStatusCode.Forbidden)
            runCatching { authService.register(params["username"].orEmpty(), params["password"].orEmpty()) }
                .onSuccess { call.respondRedirect("/webmail/login") }
                .onFailure { call.respondText(it.message ?: "register failed", status = HttpStatusCode.BadRequest) }
        }

        get("/webmail/inbox") {
            val session = call.webmailSession(authService) ?: return@get call.respondRedirect("/webmail/login")
            call.mailboxPage("Inbox", session.mailbox, mailService.inbox(session.mailbox))
        }

        get("/webmail/sent") {
            val session = call.webmailSession(authService) ?: return@get call.respondRedirect("/webmail/login")
            call.mailboxPage("Sent", session.mailbox, mailService.sent(session.mailbox))
        }

        get("/webmail/archive") {
            val session = call.webmailSession(authService) ?: return@get call.respondRedirect("/webmail/login")
            call.mailboxPage("Archive", session.mailbox, store.archive(session.mailbox))
        }

        get("/webmail/trash") {
            val session = call.webmailSession(authService) ?: return@get call.respondRedirect("/webmail/login")
            call.mailboxPage("Trash", session.mailbox, store.trash(session.mailbox), includeDeleted = true)
        }

        get("/webmail/search") {
            val session = call.webmailSession(authService) ?: return@get call.respondRedirect("/webmail/login")
            val q = call.request.queryParameters["q"].orEmpty()
            val messages = if (q.isBlank()) emptyList() else store.search(session.mailbox, q)
            call.mailboxPage("Search: $q", session.mailbox, messages, q)
        }

        get("/webmail/message/{id}") {
            val session = call.webmailSession(authService) ?: return@get call.respondRedirect("/webmail/login")
            val id = call.parameters["id"].orEmpty()
            val includeDeleted = call.request.queryParameters["deleted"] == "true"
            val message = store.findMessageForMailbox(id, session.mailbox, includeDeleted)
                ?: return@get call.respondText("Message not found", status = HttpStatusCode.NotFound)
            store.markRead(session.mailbox, id)
            call.messagePage(config, session.mailbox, message, includeDeleted)
        }

        get("/webmail/message/{messageId}/attachments/{attachmentId}") {
            val session = call.webmailSession(authService) ?: return@get call.respondRedirect("/webmail/login")
            val messageId = call.parameters["messageId"].orEmpty()
            val attachmentId = call.parameters["attachmentId"].orEmpty()
            val message = store.findMessageForMailbox(messageId, session.mailbox, includeDeleted = true)
                ?: return@get call.respondText("Message not found", status = HttpStatusCode.NotFound)
            val attachment = message.attachments.firstOrNull { it.id == attachmentId }
                ?: return@get call.respondText("Attachment not found", status = HttpStatusCode.NotFound)
            val path = attachment.path ?: return@get call.respondText("Attachment has no file", status = HttpStatusCode.NotFound)
            auditService.record(session.mailbox, "WEBMAIL_ATTACHMENT_DOWNLOAD", messageId, attachment.fileName, call.clientIp())
            call.response.headers.append("Content-Disposition", "attachment; filename=\"${attachment.fileName.replace("\"", "_")}\"")
            call.respondFile(attachmentStorage.resolve(path).toFile())
        }

        get("/webmail/compose") {
            call.webmailSession(authService) ?: return@get call.respondRedirect("/webmail/login")
            val csrf = call.ensureCsrfCookie(config)
            call.respondHtml {
                head { title("Compose") }
                body {
                    nav()
                    h1 { +"Compose" }
                    form(action = "/webmail/compose", method = FormMethod.post) {
                        csrfInput(csrf)
                        p { +"To"; br; textInput(name = "to") }
                        p { +"Subject"; br; textInput(name = "subject") }
                        p { +"Body"; br; textArea { name = "body"; rows = "12"; cols = "80" } }
                        submitInput { value = "Send" }
                    }
                }
            }
        }

        post("/webmail/compose") {
            val session = call.webmailSession(authService) ?: return@post call.respondRedirect("/webmail/login")
            val params = call.receiveParameters()
            if (!call.validCsrf(params["csrf"].orEmpty())) return@post call.respondText("CSRF validation failed", status = HttpStatusCode.Forbidden)
            runCatching {
                mailService.send(
                    session.mailbox,
                    params["to"].orEmpty().split(',', ';').map { it.trim() }.filter { it.isNotBlank() },
                    params["subject"].orEmpty(),
                    params["body"].orEmpty()
                )
            }.onSuccess {
                call.respondRedirect("/webmail/sent")
            }.onFailure {
                call.respondText(it.message ?: "send failed", status = HttpStatusCode.BadRequest)
            }
        }

        post("/webmail/message/{id}/archive") {
            val session = call.webmailSession(authService) ?: return@post call.respondRedirect("/webmail/login")
            val params = call.receiveParameters()
            if (!call.validCsrf(params["csrf"].orEmpty())) return@post call.respondText("CSRF validation failed", status = HttpStatusCode.Forbidden)
            val id = call.parameters["id"].orEmpty()
            store.setArchived(session.mailbox, id, true)
            call.respondRedirect("/webmail/archive")
        }

        post("/webmail/message/{id}/unarchive") {
            val session = call.webmailSession(authService) ?: return@post call.respondRedirect("/webmail/login")
            val params = call.receiveParameters()
            if (!call.validCsrf(params["csrf"].orEmpty())) return@post call.respondText("CSRF validation failed", status = HttpStatusCode.Forbidden)
            val id = call.parameters["id"].orEmpty()
            store.setArchived(session.mailbox, id, false)
            call.respondRedirect("/webmail/inbox")
        }

        post("/webmail/message/{id}/delete") {
            val session = call.webmailSession(authService) ?: return@post call.respondRedirect("/webmail/login")
            val params = call.receiveParameters()
            if (!call.validCsrf(params["csrf"].orEmpty())) return@post call.respondText("CSRF validation failed", status = HttpStatusCode.Forbidden)
            val id = call.parameters["id"].orEmpty()
            store.setDeleted(session.mailbox, id, true)
            call.respondRedirect("/webmail/trash")
        }

        post("/webmail/message/{id}/restore") {
            val session = call.webmailSession(authService) ?: return@post call.respondRedirect("/webmail/login")
            val params = call.receiveParameters()
            if (!call.validCsrf(params["csrf"].orEmpty())) return@post call.respondText("CSRF validation failed", status = HttpStatusCode.Forbidden)
            val id = call.parameters["id"].orEmpty()
            store.setDeleted(session.mailbox, id, false)
            call.respondRedirect("/webmail/inbox")
        }
    }
}

private suspend fun ApplicationCall.mailboxPage(
    titleText: String,
    mailbox: String,
    messages: List<MailMessage>,
    query: String = "",
    includeDeleted: Boolean = false
) {
    respondHtml {
        head { title(titleText) }
        body {
            nav()
            h1 { +titleText }
            p { +mailbox }
            form(action = "/webmail/search", method = FormMethod.get) {
                textInput { name = "q"; value = query }
                submitInput { value = "Search" }
            }
            table {
                attributes["border"] = "1"
                tr { th { +"From" }; th { +"To" }; th { +"Subject" }; th { +"Attachments" }; th { +"Time" } }
                messages.forEach { msg ->
                    tr {
                        td { +msg.from }
                        td { +msg.to.joinToString() }
                        td { a("/webmail/message/${msg.id}${if (includeDeleted) "?deleted=true" else ""}") { +msg.subject } }
                        td { +msg.attachments.size.toString() }
                        td { +msg.createdAt.toString() }
                    }
                }
            }
        }
    }
}

private suspend fun ApplicationCall.messagePage(config: AppConfig, mailbox: String, message: MailMessage, includeDeleted: Boolean) {
    val csrf = ensureCsrfCookie(config)
    respondHtml {
        head { title(message.subject) }
        body {
            nav()
            h1 { +message.subject }
            p { strong { +"From: " }; +message.from }
            p { strong { +"To: " }; +message.to.joinToString() }
            p { strong { +"Mailbox: " }; +mailbox }
            h3 { +"Body" }
            pre { +message.body }
            if (message.attachments.isNotEmpty()) {
                h3 { +"Attachments" }
                ul {
                    message.attachments.forEach { attachment ->
                        li { a("/webmail/message/${message.id}/attachments/${attachment.id}") { +"${attachment.fileName} (${attachment.size} bytes)" } }
                    }
                }
            }
            h3 { +"Actions" }
            form(action = "/webmail/message/${message.id}/archive", method = FormMethod.post) { csrfInput(csrf); submitInput { value = "Archive" } }
            form(action = "/webmail/message/${message.id}/unarchive", method = FormMethod.post) { csrfInput(csrf); submitInput { value = "Unarchive" } }
            if (includeDeleted) {
                form(action = "/webmail/message/${message.id}/restore", method = FormMethod.post) { csrfInput(csrf); submitInput { value = "Restore" } }
            } else {
                form(action = "/webmail/message/${message.id}/delete", method = FormMethod.post) { csrfInput(csrf); submitInput { value = "Move to Trash" } }
            }
        }
    }
}

private fun FlowContent.nav() {
    p {
        a("/webmail/inbox") { +"Inbox" }
        +" | "
        a("/webmail/sent") { +"Sent" }
        +" | "
        a("/webmail/archive") { +"Archive" }
        +" | "
        a("/webmail/trash") { +"Trash" }
        +" | "
        a("/webmail/compose") { +"Compose" }
        +" | "
        a("/webmail/logout") { +"Logout" }
    }
}

private fun FlowContent.csrfInput(value: String) {
    hiddenInput { name = "csrf"; this.value = value }
}

private fun ApplicationCall.webmailSession(authService: AuthService) =
    authService.authenticate("Bearer ${sessions.get<WebmailSession>()?.token.orEmpty()}")

private fun ApplicationCall.ensureCsrfCookie(config: AppConfig, force: Boolean = false): String {
    val existing = request.cookies[CSRF_COOKIE]?.takeIf { it.isNotBlank() }
    val value = if (!force && existing != null) existing else secureToken()
    response.cookies.append(webmailCookie(config, CSRF_COOKIE, value, httpOnly = false))
    return value
}

private fun ApplicationCall.validCsrf(value: String): Boolean =
    value.isNotBlank() && value == request.cookies[CSRF_COOKIE]

private fun webmailCookie(config: AppConfig, name: String, value: String, maxAge: Int = 24 * 3600, httpOnly: Boolean): Cookie = Cookie(
    name = name,
    value = value,
    path = "/webmail",
    maxAge = maxAge,
    httpOnly = httpOnly,
    secure = config.secureCookies,
    extensions = mapOf("SameSite" to "Lax")
)

private fun secureToken(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private fun ApplicationCall.clientIp(): String = request.headers["X-Forwarded-For"]?.substringBefore(',')?.trim()
    ?: request.local.remoteHost
