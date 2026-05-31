package com.maoning.mail.mime

import com.maoning.mail.attachment.AttachmentStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.nio.file.Files

class MimeParserTest {
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
}
