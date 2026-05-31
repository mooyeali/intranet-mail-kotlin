package com.maoning.mail.attachment

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

class AttachmentStorage(baseDir: String) {
    private val root: Path = Paths.get(baseDir).toAbsolutePath().normalize()

    init {
        Files.createDirectories(root)
    }

    fun save(bytes: ByteArray, originalName: String): StoredAttachment {
        val safeName = originalName.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "attachment.bin" }
        val id = UUID.randomUUID().toString()
        val dir = root.resolve(id.take(2))
        Files.createDirectories(dir)
        val path = dir.resolve("$id-$safeName").normalize()
        require(path.startsWith(root)) { "Invalid attachment path" }
        Files.write(path, bytes)
        return StoredAttachment(path = root.relativize(path).toString(), size = bytes.size.toLong())
    }

    fun resolve(relativePath: String): Path {
        val path = root.resolve(relativePath).normalize()
        require(path.startsWith(root)) { "Invalid attachment path" }
        require(Files.exists(path)) { "Attachment file not found" }
        return path
    }
}

data class StoredAttachment(val path: String, val size: Long)
