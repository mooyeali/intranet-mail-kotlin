package com.maoning.mail.attachment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.nio.file.Files

class AttachmentStorageTest {
    @Test
    fun savesAndResolvesInsideRoot() {
        val root = Files.createTempDirectory("attachments-test")
        val storage = AttachmentStorage(root.toString())
        val saved = storage.save("hello".toByteArray(), "hello.txt")
        val resolved = storage.resolve(saved.path)
        assertTrue(resolved.startsWith(root.toAbsolutePath().normalize()))
        assertEquals("hello", String(Files.readAllBytes(resolved)))
    }

    @Test
    fun rejectsPathTraversal() {
        val root = Files.createTempDirectory("attachments-test")
        val storage = AttachmentStorage(root.toString())
        assertFailsWith<IllegalArgumentException> { storage.resolve("../secret.txt") }
    }
}
