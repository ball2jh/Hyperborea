package com.nettarion.hyperborea.core

data class RetryPolicy(
    val maxAttempts: Int = 3,
    val initialDelayMs: Long = 1000,
    val maxDelayMs: Long = 30000,
    val multiplier: Double = 2.0,
) {
    fun delayForAttempt(attempt: Int): Long {
        val delay = (initialDelayMs * Math.pow(multiplier, (attempt - 1).toDouble())).toLong()
        return delay.coerceAtMost(maxDelayMs)
    }
}
