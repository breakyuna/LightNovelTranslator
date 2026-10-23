package com.breakyuna.noveltranslator.nativecore.domain

/** Explicit fixed limits. Zero RPM uses a concurrency-sized, one-second token bucket. */
data class ModelRequestLimits(val concurrency: Int = 4, val requestsPerMinute: Int = 0) {
    init {
        require(concurrency in 1..16)
        require(requestsPerMinute in 0..60_000)
    }
}
