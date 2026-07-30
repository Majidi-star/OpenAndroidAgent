package com.example.androidagent.llm

import kotlinx.coroutines.delay
import java.util.LinkedList

class RateLimiter(private val requestsPerMinute: Int) {

    private val requestTimestamps = LinkedList<Long>()

    /**
     * Acquires permission to make a request.
     * If we are currently at the rate limit, it suspends until a slot opens up.
     * Returns the wait duration in milliseconds.
     */
    suspend fun acquire(): Long {
        if (requestsPerMinute <= 0) return 0L

        while (true) {
            val now = System.currentTimeMillis()
            val windowStart = now - 60000L

            var delayTime = 0L

            synchronized(this) {
                // Remove timestamps that have slipped out of the 60-second sliding window
                while (requestTimestamps.isNotEmpty() && requestTimestamps.first() < windowStart) {
                    requestTimestamps.removeFirst()
                }

                // If we are below the rate limit, add the current timestamp and proceed
                if (requestTimestamps.size < requestsPerMinute) {
                    requestTimestamps.addLast(now)
                    return@acquire 0L
                }

                // Otherwise, calculate the remaining cooldown time based on the oldest request
                val oldestTimestamp = requestTimestamps.first()
                delayTime = (oldestTimestamp + 60000L) - now
            }

            if (delayTime > 0) {
                delay(delayTime)
            }
        }
    }
}
