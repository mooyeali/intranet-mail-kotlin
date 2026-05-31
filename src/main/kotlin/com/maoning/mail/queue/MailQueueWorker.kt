package com.maoning.mail.queue

import com.maoning.mail.config.AppConfig
import com.maoning.mail.db.H2MailStore
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class MailQueueWorker(
    private val store: H2MailStore,
    private val config: AppConfig
) {
    private val logger = LoggerFactory.getLogger(MailQueueWorker::class.java)
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val running = AtomicBoolean(false)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        executor.scheduleWithFixedDelay(::drainSafely, 1, 3, TimeUnit.SECONDS)
    }

    fun stop() {
        running.set(false)
        executor.shutdownNow()
    }

    private fun drainSafely() = runCatching { drain() }.onFailure { logger.warn("Mail queue drain failed", it) }

    fun drain(limit: Int = 50) {
        store.nextQueued(limit).forEach { item ->
            runCatching {
                store.deliverQueued(item)
                store.markQueueDelivered(item.id)
            }.onFailure { error ->
                val attempts = item.attempts + 1
                if (attempts >= config.maxQueueAttempts) {
                    store.markQueueDead(item.id, error.message ?: "delivery failed")
                } else {
                    val delaySeconds = min(3600L, 5L * (1L shl attempts.coerceAtMost(10)))
                    store.markQueueRetry(
                        item.id,
                        error.message ?: "delivery failed",
                        Instant.now().plusSeconds(delaySeconds).toEpochMilli()
                    )
                }
            }
        }
    }
}
