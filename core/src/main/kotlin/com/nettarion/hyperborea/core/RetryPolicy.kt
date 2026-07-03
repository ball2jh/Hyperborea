package com.nettarion.hyperborea.core

import kotlin.math.pow

data class RetryPolicy(
    val maxAttempts: Int = 3,
    val initialDelayMs: Long = 1000,
    val maxDelayMs: Long = 30000,
    val multiplier: Double = 2.0,
) {
    /**
     * Exponential backoff for a **1-based** attempt number: attempt 1 → [initialDelayMs],
     * attempt 2 → ×[multiplier], … capped at [maxDelayMs]. Values below 1 are clamped to 1
     * (they'd otherwise yield a shorter-than-initial delay).
     */
    fun delayForAttempt(attempt: Int): Long {
        val exponent = (attempt.coerceAtLeast(1) - 1).toDouble()
        val delay = (initialDelayMs * multiplier.pow(exponent)).toLong()
        return delay.coerceAtMost(maxDelayMs)
    }
}
