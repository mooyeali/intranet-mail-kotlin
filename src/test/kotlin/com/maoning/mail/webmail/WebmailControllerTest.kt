package com.maoning.mail.webmail

import com.maoning.mail.config.AppConfig
import org.junit.jupiter.api.io.TempDir
import org.springframework.ui.ConcurrentModel
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WebmailControllerTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun webmailIndexReturnsThymeleafTemplateWithModel() {
        val config = AppConfig(domain = "example.test", attachmentDir = tempDir.toString())
        val controller = WebmailController(config)
        val model = ConcurrentModel()

        val view = controller.index(model)

        assertEquals("webmail/index", view)
        assertEquals("example.test", model["domain"])
        assertTrue((model["apiEndpoints"] as List<*>).contains("/api/login"))
    }

    @Test
    fun webmailIndexExposesApprovedV02ApiEndpoints() {
        val config = AppConfig(domain = "example.test", attachmentDir = tempDir.toString())
        val controller = WebmailController(config)
        val model = ConcurrentModel()

        controller.index(model)

        val endpoints = model["apiEndpoints"] as List<*>
        assertTrue(endpoints.contains("/api/register"))
        assertTrue(endpoints.contains("/api/login"))
        assertTrue(endpoints.contains("/api/mail/send"))
        assertTrue(endpoints.contains("/api/mail/inbox"))
        assertTrue(endpoints.contains("/api/mail/sent"))
        assertTrue(endpoints.contains("/api/mail/search"))
        assertTrue(endpoints.contains("/api/mail/{messageId}"))
        assertTrue(endpoints.contains("/api/mail/archive"))
        assertTrue(endpoints.contains("/api/mail/trash"))
        assertTrue(endpoints.contains("/api/mail/{messageId}/read"))
        assertTrue(endpoints.contains("/api/mail/{messageId}/unread"))
        assertTrue(endpoints.contains("/api/mail/{messageId}/archive"))
        assertTrue(endpoints.contains("/api/mail/{messageId}/unarchive"))
        assertTrue(endpoints.contains("/api/mail/{messageId}/trash"))
        assertTrue(endpoints.contains("/api/mail/{messageId}/restore"))
        assertTrue(endpoints.contains("/api/mail/{messageId}/attachments/{attachmentId}"))
    }

    @Test
    fun webmailTemplateContainsFunctionalClientShellWithoutPermanentDelete() {
        val template = javaClass.classLoader.getResource("templates/webmail/index.html")?.readText()
        assertNotNull(template)

        assertTrue(template.contains("data-webmail-app"))
        assertTrue(template.contains("sessionStorage"))
        assertTrue(template.contains("webmail.session"))
        assertTrue(template.contains("Authorization"))
        assertTrue(template.contains("Login"))
        assertTrue(template.contains("Register"))
        assertTrue(template.contains("Inbox"))
        assertTrue(template.contains("Sent"))
        assertTrue(template.contains("Search"))
        assertTrue(template.contains("Compose"))
        assertTrue(template.contains("Archive"))
        assertTrue(template.contains("Trash"))
        assertTrue(template.contains("Message detail"))
        assertTrue(template.contains("Move to trash"))
        assertTrue(template.contains("Restore"))
        assertTrue(template.contains("URL.createObjectURL"))
        assertTrue(!template.contains("Delete forever"))
    }
}
