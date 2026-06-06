package com.maoning.mail.webmail

import com.maoning.mail.config.AppConfig
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

@Controller
class WebmailController(
    private val config: AppConfig
) {
    @GetMapping("/webmail")
    fun index(model: Model): String {
        model.addAttribute("title", "Intranet Mail Webmail")
        model.addAttribute("domain", config.domain)
        model.addAttribute(
            "apiEndpoints",
            listOf(
                "/api/register",
                "/api/login",
                "/api/mail/send",
                "/api/mail/inbox",
                "/api/mail/sent",
                "/api/mail/search",
                "/api/mail/{messageId}",
                "/api/mail/archive",
                "/api/mail/trash",
                "/api/mail/{messageId}/read",
                "/api/mail/{messageId}/unread",
                "/api/mail/{messageId}/archive",
                "/api/mail/{messageId}/unarchive",
                "/api/mail/{messageId}/trash",
                "/api/mail/{messageId}/restore",
                "/api/mail/{messageId}/attachments/{attachmentId}"
            )
        )
        return "webmail/index"
    }
}
