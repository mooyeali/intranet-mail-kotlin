package com.maoning.mail.api

import com.maoning.mail.attachment.AttachmentStorage
import com.maoning.mail.audit.AuditService
import com.maoning.mail.auth.AuthService
import com.maoning.mail.admin.AdminAuthService
import com.maoning.mail.config.AppConfig
import com.maoning.mail.mail.MailService
import com.maoning.mail.queue.MailQueueWorker
import com.maoning.mail.security.LoginRateLimiter
import com.maoning.mail.store.MailStore
import com.maoning.mail.store.MailboxState
import com.maoning.mail.store.QueueItem
import com.maoning.mail.store.QueueStatus
import javax.servlet.http.HttpServletRequest
import org.springframework.core.io.FileSystemResource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.InetAddress
import java.time.Duration
import java.time.Instant

data class RegisterRequest(val username: String = "", val password: String = "")
data class LoginRequest(val username: String = "", val password: String = "")
data class SendMailRequest(val to: List<String> = emptyList(), val subject: String = "", val body: String = "")
data class UserResponse(val id: String, val username: String, val mailbox: String)
data class LoginResponse(val token: String, val mailbox: String, val expiresAt: Long)
data class ErrorResponse(val error: String)
data class HealthResponse(val status: String, val domain: String, val timestamp: Long = Instant.now().toEpochMilli())
data class ReadinessResponse(val status: String, val domain: String, val checks: Map<String, String>, val timestamp: Long = Instant.now().toEpochMilli())
data class QueueMetricsResponse(val queued: Int, val retry: Int, val delivered: Int, val dead: Int, val total: Int)
data class MailboxStateResponse(val messageId: String, val mailbox: String, val box: String, val read: Boolean, val archived: Boolean, val deleted: Boolean)
data class DeliveryStatusResponse(val messageId: String, val recipients: List<DeliveryRecipientStatusResponse>)
data class DeliveryRecipientStatusResponse(val recipient: String, val status: QueueStatus, val attempts: Int, val lastError: String?, val nextAttemptAt: Long, val updatedAt: Long)

@RestController
class MailController(
    private val config: AppConfig,
    private val authService: AuthService,
    private val mailService: MailService,
    private val store: MailStore,
    private val queueWorker: MailQueueWorker,
    private val auditService: AuditService,
    private val attachmentStorage: AttachmentStorage,
    private val adminAuthService: AdminAuthService,
    private val loginRateLimiter: LoginRateLimiter? = null
) {
    @GetMapping("/health")
    fun health() = HealthResponse("ok", config.domain)

    @GetMapping("/health/live")
    fun liveness() = HealthResponse("ok", config.domain)

    @GetMapping("/health/ready")
    fun readiness(): ResponseEntity<Any> {
        val checks = linkedMapOf<String, String>()
        runCatching { store.users(); store.queueItems() }
            .onSuccess { checks["store"] = "ok" }
            .onFailure { checks["store"] = "failed: ${it.message ?: it.javaClass.simpleName}" }
        val status = if (checks.values.all { it == "ok" }) "ready" else "not-ready"
        val httpStatus = if (status == "ready") HttpStatus.OK else HttpStatus.SERVICE_UNAVAILABLE
        return ResponseEntity.status(httpStatus).body(ReadinessResponse(status, config.domain, checks))
    }

    @GetMapping("/metrics/queue")
    fun queueMetrics(): QueueMetricsResponse {
        val items = store.queueItems()
        fun count(status: QueueStatus) = items.count { it.status == status }
        return QueueMetricsResponse(
            queued = count(QueueStatus.QUEUED),
            retry = count(QueueStatus.RETRY),
            delivered = count(QueueStatus.DELIVERED),
            dead = count(QueueStatus.DEAD),
            total = items.size
        )
    }

    @PostMapping("/api/register")
    fun register(@RequestBody req: RegisterRequest, servlet: HttpServletRequest): ResponseEntity<Any> = runCatching {
        val user = authService.register(req.username, req.password)
        auditService.record(user.mailbox, "USER_REGISTER", user.mailbox, ip = servlet.clientIp(config))
        UserResponse(user.id, user.username, user.mailbox)
    }.fold({ ResponseEntity.status(HttpStatus.CREATED).body(it) }, { bad(it, HttpStatus.BAD_REQUEST) })

    @PostMapping("/api/login")
    fun login(@RequestBody req: LoginRequest, servlet: HttpServletRequest): ResponseEntity<Any> {
        val ip = servlet.clientIp(config)
        return runCatching {
            val session = authService.login(req.username, req.password, ip)
            auditService.record(session.mailbox, "USER_LOGIN", session.mailbox, ip = ip)
            LoginResponse(session.token, session.mailbox, session.expiresAt)
        }.fold({ ResponseEntity.ok(it) }, {
            auditService.record(req.username, "USER_LOGIN_FAILED", req.username, it.message, ip)
            bad(it, HttpStatus.UNAUTHORIZED)
        })
    }

    @PostMapping("/api/mail/send")
    fun send(@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) auth: String?, @RequestBody req: SendMailRequest): ResponseEntity<Any> {
        if (req.to.size > config.maxRecipients) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse("recipient count exceeds MAX_RECIPIENTS (${config.maxRecipients})"))
        }
        if (req.messageSizeBytes() > config.maxMessageBytes) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(ErrorResponse("message exceeds MAX_MESSAGE_BYTES"))
        }
        val session = authService.authenticate(auth) ?: return unauthorized()
        return runCatching { mailService.send(session.mailbox, req.to, req.subject, req.body) }
            .fold({ ResponseEntity.status(HttpStatus.CREATED).body(it) }, { bad(it, HttpStatus.BAD_REQUEST) })
    }

    @GetMapping("/api/mail/inbox")
    fun inbox(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) auth: String?,
        @RequestParam("limit", defaultValue = "100") limit: Int = 100,
        @RequestParam("offset", defaultValue = "0") offset: Int = 0
    ): ResponseEntity<Any> {
        val session = authService.authenticate(auth) ?: return unauthorized()
        return ResponseEntity.ok(mailService.inbox(session.mailbox, mailboxLimit(limit), mailboxOffset(offset)))
    }

    @GetMapping("/api/mail/sent")
    fun sent(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) auth: String?,
        @RequestParam("limit", defaultValue = "100") limit: Int = 100,
        @RequestParam("offset", defaultValue = "0") offset: Int = 0
    ): ResponseEntity<Any> {
        val session = authService.authenticate(auth) ?: return unauthorized()
        return ResponseEntity.ok(mailService.sent(session.mailbox, mailboxLimit(limit), mailboxOffset(offset)))
    }

    @GetMapping("/api/mail/search")
    fun search(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) auth: String?,
        @RequestParam("q", defaultValue = "") q: String,
        @RequestParam("box", required = false) box: String?,
        @RequestParam("limit", defaultValue = "100") limit: Int
    ): ResponseEntity<Any> {
        val session = authService.authenticate(auth) ?: return unauthorized()
        return ResponseEntity.ok(store.search(session.mailbox, q, box?.takeIf { it == "inbox" || it == "sent" }, limit.coerceIn(1, 500)))
    }

    @GetMapping("/api/mail/archive")
    fun archive(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) auth: String?,
        @RequestParam("limit", defaultValue = "100") limit: Int = 100,
        @RequestParam("offset", defaultValue = "0") offset: Int = 0
    ): ResponseEntity<Any> {
        val session = authService.authenticate(auth) ?: return unauthorized()
        return ResponseEntity.ok(store.archive(session.mailbox, mailboxLimit(limit), mailboxOffset(offset)))
    }

    @GetMapping("/api/mail/trash")
    fun trash(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) auth: String?,
        @RequestParam("limit", defaultValue = "100") limit: Int = 100,
        @RequestParam("offset", defaultValue = "0") offset: Int = 0
    ): ResponseEntity<Any> {
        val session = authService.authenticate(auth) ?: return unauthorized()
        return ResponseEntity.ok(store.trash(session.mailbox, mailboxLimit(limit), mailboxOffset(offset)))
    }

    @GetMapping("/api/mail/{messageId}")
    fun messageDetail(@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) auth: String?, @PathVariable messageId: String): ResponseEntity<Any> {
        val session = authService.authenticate(auth) ?: return unauthorized()
        val message = store.findMessageForMailbox(messageId, session.mailbox) ?: return messageNotFound()
        return ResponseEntity.ok(message)
    }

    @GetMapping("/api/mail/{messageId}/delivery-status")
    fun deliveryStatus(@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) auth: String?, @PathVariable messageId: String): ResponseEntity<Any> {
        val session = authService.authenticate(auth) ?: return unauthorized()
        val sentMessage = store.findSentMessage(messageId, session.mailbox) ?: return messageNotFound()
        val statuses = store.queueItemsForMessage(sentMessage.id).filter { it.messageId == sentMessage.id }
        return ResponseEntity.ok(DeliveryStatusResponse(sentMessage.id, statuses.map { it.toDeliveryRecipientStatus() }))
    }

    @PostMapping("/api/mail/{messageId}/read")
    fun markRead(@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) auth: String?, @PathVariable messageId: String): ResponseEntity<Any> =
        mailboxAction(auth, messageId) { mailbox, id -> store.markRead(mailbox, id, true) }

    @PostMapping("/api/mail/{messageId}/unread")
    fun markUnread(@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) auth: String?, @PathVariable messageId: String): ResponseEntity<Any> =
        mailboxAction(auth, messageId) { mailbox, id -> store.markRead(mailbox, id, false) }

    @PostMapping("/api/mail/{messageId}/archive")
    fun archiveMessage(@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) auth: String?, @PathVariable messageId: String): ResponseEntity<Any> =
        mailboxAction(auth, messageId) { mailbox, id -> store.setArchived(mailbox, id, true) }

    @PostMapping("/api/mail/{messageId}/unarchive")
    fun unarchiveMessage(@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) auth: String?, @PathVariable messageId: String): ResponseEntity<Any> =
        mailboxAction(auth, messageId) { mailbox, id -> store.setArchived(mailbox, id, false) }

    @PostMapping("/api/mail/{messageId}/trash")
    fun trashMessage(@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) auth: String?, @PathVariable messageId: String): ResponseEntity<Any> =
        mailboxAction(auth, messageId) { mailbox, id -> store.setDeleted(mailbox, id, true) }

    @PostMapping("/api/mail/{messageId}/restore")
    fun restoreMessage(@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) auth: String?, @PathVariable messageId: String): ResponseEntity<Any> =
        mailboxAction(auth, messageId, includeDeleted = true) { mailbox, id -> store.setDeleted(mailbox, id, false) }

    @GetMapping("/api/mail/{messageId}/attachments/{attachmentId}")
    fun attachment(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) auth: String?,
        @PathVariable messageId: String,
        @PathVariable attachmentId: String,
        servlet: HttpServletRequest
    ): ResponseEntity<Any> {
        val session = authService.authenticate(auth) ?: return unauthorized()
        val message = store.findMessageForMailbox(messageId, session.mailbox) ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse("message not found"))
        val attachment = message.attachments.firstOrNull { it.id == attachmentId } ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse("attachment not found"))
        val path = attachment.path ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse("attachment has no file"))
        auditService.record(session.mailbox, "ATTACHMENT_DOWNLOAD", messageId, attachment.fileName, servlet.clientIp(config))
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"${attachment.fileName.replace("\"", "_")}\"")
            .body(FileSystemResource(attachmentStorage.resolve(path).toFile()))
    }

    @PostMapping("/admin/login")
    fun adminLogin(@RequestParam username: String, @RequestParam password: String, servlet: HttpServletRequest): ResponseEntity<Any> {
        val ip = servlet.clientIp(config)
        val limiterKey = "admin:$username"
        runCatching { loginRateLimiter?.assertAllowed(limiterKey, ip) }
            .onFailure { return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(ErrorResponse(it.message ?: "too many login attempts")) }
        return if (username == config.adminUser && adminAuthService.passwordMatches(password)) {
            loginRateLimiter?.record(limiterKey, ip, success = true)
            auditService.record(username, "ADMIN_LOGIN", ip = servlet.clientIp(config))
            val body = mutableMapOf<String, String>("status" to "ok")
            val builder = ResponseEntity.ok()
            if (config.adminSessionSecret.isNotBlank()) {
                val session = adminAuthService.createSessionCookieValue(username)
                body["session"] = session
                val cookie = ResponseCookie.from("ADMIN_SESSION", session)
                    .httpOnly(true)
                    .secure(config.secureCookies)
                    .sameSite("Strict")
                    .path("/")
                    .maxAge(Duration.ofHours(config.adminSessionHours))
                    .build()
                builder.header(HttpHeaders.SET_COOKIE, cookie.toString())
            }
            builder.body(body)
        } else {
            loginRateLimiter?.record(limiterKey, ip, success = false)
            auditService.record(username, "ADMIN_LOGIN_FAILED", ip = ip)
            ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorResponse("invalid admin credentials"))
        }
    }

    @GetMapping("/dns", produces = ["text/plain"])
    fun dns(): String = """
        # 内网 DNS 示例 zone 记录
        ${config.domain}.        IN MX 10 mail.${config.domain}.
        mail.${config.domain}.   IN A  192.168.1.10
        smtp.${config.domain}.   IN CNAME mail.${config.domain}.
        pop3.${config.domain}.   IN CNAME mail.${config.domain}.
        _smtp._tcp.${config.domain}. IN SRV 0 5 ${config.smtpPort} mail.${config.domain}.
        _pop3._tcp.${config.domain}. IN SRV 0 5 ${config.pop3Port} mail.${config.domain}.
    """.trimIndent()

    private data class AdminAuthContext(
        val headerToken: String? = null,
        val queryToken: String? = null,
        val sessionCookie: String? = null,
    )

    private fun adminAuthContext(
        headerToken: String?,
        queryToken: String?,
        sessionCookie: String?
    ) = AdminAuthContext(headerToken = headerToken, queryToken = queryToken, sessionCookie = sessionCookie)

    @GetMapping("/admin/api/users") fun adminUsers(
        @RequestHeader("X-Admin-Token", required = false) token: String?,
        @RequestParam("token", required = false) queryToken: String?,
        @CookieValue("ADMIN_SESSION", required = false) sessionCookie: String?
    ) = adminOr401(adminAuthContext(token, queryToken, sessionCookie)) { store.users() }

    @GetMapping("/admin/api/messages") fun adminMessages(
        @RequestHeader("X-Admin-Token", required = false) token: String?,
        @RequestParam("token", required = false) queryToken: String?,
        @CookieValue("ADMIN_SESSION", required = false) sessionCookie: String?
    ) = adminOr401(adminAuthContext(token, queryToken, sessionCookie)) { store.messages(200) }

    @GetMapping("/admin/api/queue") fun adminQueue(
        @RequestHeader("X-Admin-Token", required = false) token: String?,
        @RequestParam("token", required = false) queryToken: String?,
        @CookieValue("ADMIN_SESSION", required = false) sessionCookie: String?
    ) = adminOr401(adminAuthContext(token, queryToken, sessionCookie)) { store.queueItems() }

    @GetMapping("/admin/api/dead") fun adminDead(
        @RequestHeader("X-Admin-Token", required = false) token: String?,
        @RequestParam("token", required = false) queryToken: String?,
        @CookieValue("ADMIN_SESSION", required = false) sessionCookie: String?
    ) = adminOr401(adminAuthContext(token, queryToken, sessionCookie)) { store.queueItems(QueueStatus.DEAD) }

    @GetMapping("/admin/api/audit") fun adminAudit(
        @RequestHeader("X-Admin-Token", required = false) token: String?,
        @RequestParam("token", required = false) queryToken: String?,
        @CookieValue("ADMIN_SESSION", required = false) sessionCookie: String?
    ) = adminOr401(adminAuthContext(token, queryToken, sessionCookie)) { auditService.recent(200) }

    @PostMapping("/admin/api/queue/drain") fun drain(
        @RequestHeader("X-Admin-Token", required = false) token: String?,
        @RequestParam("token", required = false) queryToken: String?,
        @CookieValue("ADMIN_SESSION", required = false) sessionCookie: String?,
        @RequestHeader("X-CSRF-Token", required = false) csrfToken: String?
    ) = adminPostOr401(adminAuthContext(token, queryToken, sessionCookie), csrfToken) { queueWorker.drain(); mapOf("status" to "ok") }

    private fun adminOr401(auth: AdminAuthContext, body: () -> Any): ResponseEntity<Any> =
        if (adminAuthService.isAdminAuthorized(auth.headerToken, auth.queryToken, auth.sessionCookie)) {
            adminOk(auth, body())
        } else {
            ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorResponse("admin token required"))
        }

    private fun adminOk(auth: AdminAuthContext, body: Any): ResponseEntity<Any> {
        val builder = ResponseEntity.ok()
        if (!auth.queryToken.isNullOrBlank()) {
            builder.header("Referrer-Policy", "no-referrer")
            builder.header(HttpHeaders.CACHE_CONTROL, "no-store")
        }
        return builder.body(body)
    }

    private fun adminPostOr401(auth: AdminAuthContext, csrfToken: String?, body: () -> Any): ResponseEntity<Any> = when {
        adminAuthService.isAdminToken(auth.headerToken) -> ResponseEntity.ok(body())
        !auth.queryToken.isNullOrBlank() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorResponse("admin header token required for POST"))
        adminAuthService.isAdminSession(auth.sessionCookie) && !adminAuthService.isValidCsrfToken(auth.sessionCookie, csrfToken) ->
            ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse("csrf token required"))
        adminAuthService.isAdminSession(auth.sessionCookie) -> ResponseEntity.ok(body())
        else -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorResponse("admin token required"))
    }

    private fun mailboxAction(
        auth: String?,
        messageId: String,
        includeDeleted: Boolean = false,
        action: (mailbox: String, messageId: String) -> Unit
    ): ResponseEntity<Any> {
        val session = authService.authenticate(auth) ?: return unauthorized()
        store.mailboxState(session.mailbox, messageId, includeDeleted) ?: return messageNotFound()
        action(session.mailbox, messageId)
        val updated = store.mailboxState(session.mailbox, messageId, includeDeleted = true) ?: return messageNotFound()
        return ResponseEntity.ok(updated.toResponse())
    }

    private fun unauthorized(): ResponseEntity<Any> = ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorResponse("missing or invalid token"))
    private fun messageNotFound(): ResponseEntity<Any> = ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse("message not found"))
    private fun bad(t: Throwable, status: HttpStatus): ResponseEntity<Any> = ResponseEntity.status(status).body(ErrorResponse(t.message ?: "request failed"))
}

private fun MailboxState.toResponse() = MailboxStateResponse(messageId, mailbox, box, read, archived, deleted)
private fun QueueItem.toDeliveryRecipientStatus() = DeliveryRecipientStatusResponse(recipient, status, attempts, lastError, nextAttemptAt, updatedAt)
private fun mailboxLimit(limit: Int) = limit.coerceIn(1, 200)
private fun mailboxOffset(offset: Int) = offset.coerceAtLeast(0)

private fun SendMailRequest.messageSizeBytes(): Long =
    (to.sumOf { it.toByteArray(Charsets.UTF_8).size } + subject.toByteArray(Charsets.UTF_8).size + body.toByteArray(Charsets.UTF_8).size).toLong()

internal fun HttpServletRequest.clientIp(config: AppConfig): String {
    val remote = remoteAddr.orEmpty()
    if (!isTrustedProxy(remote, config.trustedProxyCidrs)) return remote
    forwardedFor()?.let { return it }
    getHeader("X-Forwarded-For")
        ?.split(',')
        ?.firstOrNull()
        ?.trim()
        ?.trim('[', ']')
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }
    return remote
}

private fun HttpServletRequest.forwardedFor(): String? = getHeader("Forwarded")
    ?.split(',')
    ?.firstOrNull()
    ?.split(';')
    ?.map { it.trim() }
    ?.firstOrNull { it.startsWith("for=", ignoreCase = true) }
    ?.substringAfter('=')
    ?.trim('"')
    ?.trim('[', ']')
    ?.takeIf { it.isNotBlank() }

private fun isTrustedProxy(remote: String, cidrs: List<String>): Boolean =
    cidrs.any { cidr -> runCatching { ipInCidr(remote, cidr) }.getOrDefault(false) }

private fun ipInCidr(ip: String, cidr: String): Boolean {
    val parts = cidr.split('/', limit = 2)
    val address = InetAddress.getByName(ip)
    val network = InetAddress.getByName(parts[0])
    val prefix = parts.getOrNull(1)?.toIntOrNull() ?: (address.address.size * 8)
    if (address.address.size != network.address.size) return false
    val addressBytes = address.address
    val networkBytes = network.address
    var remaining = prefix
    for (i in addressBytes.indices) {
        val bits = remaining.coerceIn(0, 8)
        if (bits == 0) return true
        val mask = ((0xff shl (8 - bits)) and 0xff)
        if ((addressBytes[i].toInt() and mask) != (networkBytes[i].toInt() and mask)) return false
        remaining -= bits
    }
    return true
}
