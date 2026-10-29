package cn.coldlake.stellar.opensource.network

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal class HttpRateLimited(val retryAt: Long) : IOException("HTTP 429")

internal fun retryAfterMillis(header: String?, now: Long, fallback: Long = 5_000): Long {
    val value = header?.trim()
    return (value?.toLongOrNull()?.coerceIn(1, 86400)?.times(1000)
        ?: value?.let { runCatching {
            ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now
        }.getOrNull() } ?: fallback).coerceIn(1_000, 86_400_000)
}

internal fun checkRateLimit(status: Int, retryAfter: String?) {
    if (status == 429) {
        val now = System.currentTimeMillis()
        throw HttpRateLimited(now + retryAfterMillis(retryAfter, now))
    }
}

/** Read operations only. Each attempt must close its response and release permits before throwing. */
internal suspend fun <T> rateLimitedRead(clock: () -> Long = System::currentTimeMillis,
    wait: suspend (Long) -> Unit = { delay(it) }, read: suspend () -> T): T {
    var attempts = 0
    var waited = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        try { attempts++; return read() }
        catch (limited: HttpRateLimited) {
            val remaining = (limited.retryAt - clock()).coerceAtLeast(1_000)
            if (attempts >= 3 || remaining > MAX_RATE_WAIT - waited) throw limited
            wait(remaining)
            waited += remaining
        }
    }
}

internal const val MAX_RATE_WAIT = 120_000L
