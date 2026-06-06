package com.maoning.mail.queue

import com.maoning.mail.config.AppConfig
import com.maoning.mail.store.MailStore
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import kotlin.math.min

@Component
class MailQueueWorker(
    private val store: MailStore,
    private val config: AppConfig
) {
    private val logger = LoggerFactory.getLogger(MailQueueWorker::class.java)

    fun start() {}
    fun stop() {}

    @Scheduled(fixedDelay = 3000, initialDelay = 1000)
    fun drainSafely() = runCatching { drain() }.onFailure { logger.warn("Mail queue drain failed", it) }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    fun cleanupExpiredSessionsSafely() = runCatching {
        store.deleteExpiredSessions(Instant.now().toEpochMilli())
    }.onFailure { logger.warn("Session cleanup failed", it) }

    fun drain(limit: Int = 50) {
        store.nextQueued(limit).forEach { item ->
            if (!store.markQueueInProgress(item.id, item.attempts)) {
                return@forEach
            }
            runCatching {
                store.deliverQueued(item)
                store.markQueueDelivered(item.id)
            }.onFailure { error ->
                val attempts = item.attempts + 1
                if (attempts >= config.maxQueueAttempts) {
                    store.markQueueDead(item.id, error.message ?: "delivery failed", attempts)
                } else {
                    val delaySeconds = min(3600L, 5L * (1L shl attempts.coerceAtMost(10)))
                    store.markQueueRetry(item.id, error.message ?: "delivery failed", Instant.now().plusSeconds(delaySeconds).toEpochMilli(), attempts)
                }
            }
        }
    }
}
