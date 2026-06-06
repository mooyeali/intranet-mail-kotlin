package com.maoning.mail.mime

import com.maoning.mail.attachment.AttachmentStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.nio.file.Files

class MimeParserTest {
    @Test
    fun decodesEncodedSubjectAndFilename() {
        val root = Files.createTempDirectory("mime-encoded-test")
        val parser = MimeParser(AttachmentStorage(root.toString()))
        val raw = """
            Subject: =?UTF-8?B?5rWL6K+V5Li76aKY?=
            Content-Type: multipart/mixed; boundary="demo"

            --demo
            Content-Type: text/plain; charset=utf-8

            hello body
            --demo
            Content-Type: text/plain; name="=?UTF-8?B?5rWL6K+VLnR4dA==?="
            Content-Disposition: attachment; filename="=?UTF-8?B?5rWL6K+VLnR4dA==?="
            Content-Transfer-Encoding: base64

            aGVsbG8gZmlsZQ==
            --demo--
        """.trimIndent().replace("\n", "\r\n")

        val parsed = parser.parse(raw)

        assertEquals("测试主题", parsed.subject)
        assertEquals("测试.txt", parsed.attachments.first().fileName)
    }

    @Test
    fun extractsReadableHtmlTextAndDecodesEntities() {
        val root = Files.createTempDirectory("mime-html-test")
        val parser = MimeParser(AttachmentStorage(root.toString()))
        val raw = """
            Subject: HTML
            Content-Type: text/html; charset=utf-8

            <html><body><p>Hello&nbsp;<strong>Bob</strong></p><script>evil()</script></body></html>
        """.trimIndent().replace("\n", "\r\n")

        val parsed = parser.parse(raw)

        assertTrue(parsed.body.contains("Hello Bob"))
        assertTrue(!parsed.body.contains("evil()"))
    }

    @Test
    fun parsesMultipartWithAttachment() {
        val root = Files.createTempDirectory("mime-test")
        val parser = MimeParser(AttachmentStorage(root.toString()))
        val raw = """
            Subject: Demo
            Content-Type: multipart/mixed; boundary="demo"

            --demo
            Content-Type: text/plain; charset=utf-8

            hello body
            --demo
            Content-Type: text/plain; name="a.txt"
            Content-Disposition: attachment; filename="a.txt"
            Content-Transfer-Encoding: base64

            aGVsbG8gZmlsZQ==
            --demo--
        """.trimIndent().replace("\n", "\r\n")
        val parsed = parser.parse(raw)
        assertEquals("Demo", parsed.subject)
        assertTrue(parsed.body.contains("hello body"))
        assertEquals(1, parsed.attachments.size)
        assertEquals("a.txt", parsed.attachments.first().fileName)
        assertTrue(parsed.attachments.first().path != null)
    }

    @Test
    fun rejectsAttachmentLargerThanPerFileLimit() {
        val root = Files.createTempDirectory("mime-attachment-size-test")
        val parser = MimeParser(AttachmentStorage(root.toString()), maxAttachmentBytes = 4, maxTotalAttachmentBytes = 100)
        val raw = multipartWithAttachments(listOf("large.txt" to "abcde"))

        assertFailsWith<IllegalArgumentException> {
            parser.parse(raw)
        }
    }

    @Test
    fun rejectsAttachmentsWhenCombinedSizeExceedsTotalLimit() {
        val root = Files.createTempDirectory("mime-total-size-test")
        val parser = MimeParser(AttachmentStorage(root.toString()), maxAttachmentBytes = 10, maxTotalAttachmentBytes = 8)
        val raw = multipartWithAttachments(listOf("a.txt" to "abcd", "b.txt" to "efghi"))

        assertFailsWith<IllegalArgumentException> {
            parser.parse(raw)
        }
    }

    @Test
    fun malformedMimeWithoutAttachmentFailsInsteadOfSilentlyDroppingParts() {
        val root = Files.createTempDirectory("mime-malformed-test")
        val parser = MimeParser(AttachmentStorage(root.toString()))
        val raw = "Subject: Broken\r\nContent-Type: multipart/mixed; boundary=missing\r\n\r\nthis is not a valid multipart body"

        assertFailsWith<jakarta.mail.internet.ParseException> {
            parser.parse(raw)
        }
    }

    private fun multipartWithAttachments(files: List<Pair<String, String>>): String = buildString {
        append("Subject: Attachments\r\n")
        append("Content-Type: multipart/mixed; boundary=\"demo\"\r\n\r\n")
        append("--demo\r\n")
        append("Content-Type: text/plain; charset=utf-8\r\n\r\n")
        append("body\r\n")
        files.forEach { (name, content) ->
            val encoded = java.util.Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8))
            append("--demo\r\n")
            append("Content-Type: text/plain; name=\"").append(name).append("\"\r\n")
            append("Content-Disposition: attachment; filename=\"").append(name).append("\"\r\n")
            append("Content-Transfer-Encoding: base64\r\n\r\n")
            append(encoded).append("\r\n")
        }
        append("--demo--\r\n")
    }
}
