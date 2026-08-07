package com.example.myapplication3.network.ratelimit

import com.example.myapplication3.core.common.AppException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RateLimiter(
    private val maxRequestsPerMinute: Int,
    private val providerName: String
) {
    private val mutex = Mutex()
    private val requestTimestamps = ArrayDeque<Long>()

    suspend fun acquire() {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val windowStart = now - 60_000L
            while (requestTimestamps.isNotEmpty() && requestTimestamps.first() < windowStart) {
                requestTimestamps.removeFirst()
            }

            if (requestTimestamps.size >= maxRequestsPerMinute) {
                val oldestRequest = requestTimestamps.first()
                val waitMs = (oldestRequest + 60_000L) - now + 100L
                if (waitMs > 0) {
                    throw AppException.RateLimitException(
                        "Rate limit reached for $providerName",
                        waitMs
                    )
                }
            }

            requestTimestamps.addLast(now)
        }
    }

    suspend fun acquireWithWait() {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val windowStart = now - 60_000L
            while (requestTimestamps.isNotEmpty() && requestTimestamps.first() < windowStart) {
                requestTimestamps.removeFirst()
            }

            if (requestTimestamps.size >= maxRequestsPerMinute) {
                val oldestRequest = requestTimestamps.first()
                val waitMs = (oldestRequest + 60_000L) - now + 100L
                if (waitMs > 0) delay(waitMs)
            }

            requestTimestamps.addLast(System.currentTimeMillis())
        }
    }
}
