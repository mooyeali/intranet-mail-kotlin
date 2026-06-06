package com.maoning.mail.admin

import com.maoning.mail.audit.AuditService
import com.maoning.mail.config.AppConfig
import com.maoning.mail.store.MailStore
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam

@Controller
class AdminViewController(
    private val adminAuthService: AdminAuthService,
    private val config: AppConfig,
    private val store: MailStore,
    private val auditService: AuditService
) {
    @GetMapping("/admin/login")
    fun login(model: Model): String {
        model.addAttribute("title", "Admin Login")
        model.addAttribute("loginEndpoint", "/admin/login")
        return "admin/login"
    }

    @GetMapping("/admin")
    fun dashboard(
        @RequestHeader("X-Admin-Token", required = false) token: String?,
        @RequestParam("token", required = false) queryToken: String?,
        @CookieValue("ADMIN_SESSION", required = false) sessionCookie: String?,
        model: Model
    ): String {
        if (!adminAuthService.isAdminAuthorized(token, queryToken, sessionCookie)) {
            model.addAttribute("title", "Admin Login")
            model.addAttribute("loginEndpoint", "/admin/login")
            return "admin/login"
        }

        val users = store.users()
        val messages = store.messages(200)
        val queue = store.queueItems()
        model.addAttribute("title", "Intranet Mail Admin")
        model.addAttribute("domain", config.domain)
        model.addAttribute("userCount", users.size)
        model.addAttribute("messageCount", messages.size)
        model.addAttribute("queueCount", queue.size)
        model.addAttribute("deadQueueCount", queue.count { it.status.name == "DEAD" })
        model.addAttribute("recentUsers", users.take(20))
        model.addAttribute("recentMessages", messages.take(20))
        model.addAttribute("queueItems", queue.take(20))
        model.addAttribute("auditEvents", auditService.recent(20))
        model.addAttribute("apiEndpoints", listOf("/admin/api/users", "/admin/api/messages", "/admin/api/queue", "/admin/api/dead", "/admin/api/audit"))
        if (sessionCookie != null && adminAuthService.isAdminSession(sessionCookie)) {
            model.addAttribute("csrfToken", adminAuthService.createCsrfToken(sessionCookie))
        }
        return "admin/dashboard"
    }
}
