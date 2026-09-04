package com.ethran.notable.io.obsidiansync

internal object ObsidianApiRetry {

    fun <T> withRetry(
        maxAttempts: Int = 3,
        retryDelayMs: Long = 2_000L,
        block: () -> T
    ): T {
        var last: ObsidianApiException? = null
        repeat(maxAttempts) { attempt ->
            try {
                return block()
            } catch (e: ObsidianApiException) {
                last = e
                if (!e.isRetryable() || attempt == maxAttempts - 1) throw e
                Thread.sleep(retryDelayMs)
            }
        }
        throw last ?: IllegalStateException("retry exhausted")
    }
}

internal fun ObsidianApiException.isRetryable(): Boolean {
    val msg = apiMessage.lowercase()
    return msg.contains("overloaded") ||
        msg.contains("try again") ||
        msg.contains("rate limit")
}
