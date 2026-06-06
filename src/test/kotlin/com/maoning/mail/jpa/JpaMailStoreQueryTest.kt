package com.maoning.mail.jpa

import com.maoning.mail.store.Attachment
import com.maoning.mail.store.MailMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class JpaMailStoreQueryTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun repositoryExposesSearchProjectionWithQueryAndLimit() {
        val method = MailboxRepository::class.java.methods.single { it.name == "searchMailboxMessageRows" }

        assertEquals(
            listOf(
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                org.springframework.data.domain.Pageable::class.java
            ),
            method.parameterTypes.toList()
        )
        assertEquals(List::class.java, method.returnType)
    }

    @Test
    fun repositoryExposesBoundedMailboxProjectionAndAtomicQueueClaim() {
        val mailboxMethod = MailboxRepository::class.java.methods.single { it.name == "findMailboxMessageRows" }
        assertEquals(
            listOf(
                String::class.java,
                String::class.java,
                java.lang.Boolean::class.java,
                java.lang.Boolean.TYPE,
                org.springframework.data.domain.Pageable::class.java
            ),
            mailboxMethod.parameterTypes.toList()
        )

        val claimMethod = QueueRepository::class.java.methods.single { it.name == "claimReady" }
        assertEquals(Int::class.javaPrimitiveType, claimMethod.returnType)
        assertEquals(
            listOf(
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Collection::class.java
            ),
            claimMethod.parameterTypes.toList()
        )
    }

    @Test
    fun mailboxProjectionConvertsJoinedRowsWithoutAdditionalMessageLookup() {
        val entity = MessageEntity(
            id = "m1",
            sender = "alice@example.test",
            recipients = json.encodeToString(listOf("bob@example.test")),
            subject = "Hello",
            body = "Body",
            raw = null,
            attachments = json.encodeToString(emptyList<Attachment>()),
            readFlag = false,
            createdAt = 123
        )

        val row = MailboxMessageRow(entity, mailboxRead = true)
        val message = row.toDomain(json)

        assertEquals(MailMessage("m1", "alice@example.test", listOf("bob@example.test"), "Hello", "Body", null, emptyList(), true, 123), message)
    }
}
