package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.ModelFailureKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyPoolTest {
    @Test
    fun poolTrimsDeduplicatesAndRedactsEveryStringRepresentation() {
        val pool = KeyPool.of(listOf(" super-secret-a ", "", "super-secret-b", "super-secret-a"))
        assertEquals(2, pool.size)
        assertEquals(listOf("key-1", "key-2"), pool.entries.map { it.id })
        assertEquals("super-secret-a", pool.entries.first().credential)
        assertFalse(pool.toString().contains("super-secret"))
        assertFalse(pool.entries.first().toString().contains("super-secret"))
        assertFalse(pool.select(0)!!.toString().contains("super-secret"))
        assertEquals(2, KeyPool.fromRawCredential(" a \r\n\r\n b\r\n a ").size)
        assertEquals(0, KeyPool.of(emptyList()).size)
        assertNull(KeyPool.of(emptyList()).select(0))
    }

    @Test
    fun selectionIsRoundRobinAndSkipsDisabledKeys() {
        val pool = KeyPool.of(listOf("a", "b"))
        val first = pool.select(0)!!
        val second = first.pool.select(0)!!
        val third = second.pool.select(0)!!
        assertEquals("key-1", first.entry.id)
        assertEquals("key-2", second.entry.id)
        assertEquals("key-1", third.entry.id)

        val onlySecond = pool.setEnabled("key-1", false)
        assertEquals(1, onlySecond.enabledCount)
        assertEquals("key-2", onlySecond.select(0)!!.entry.id)

        val restored = pool.setEnabled("key-1", false).setEnabled("key-1", true)
        assertEquals(2, restored.enabledCount)
        assertEquals("key-1", restored.select(0)!!.entry.id)
    }

    @Test
    fun cooldownLadderGrowsAndSuccessResetsIt() {
        val start = 1_000L
        var pool = KeyPool.of(listOf("a"))
        pool = pool.recordFailure("key-1", KeyFailureKind.NETWORK, start)
        assertNull(pool.select(start))
        assertNull(pool.select(start + 14_999))
        assertNotNull(pool.select(start + 15_000))
        assertEquals(start + 15_000, pool.nextAvailableAt(start + 1)!!)

        pool = pool.recordFailure("key-1", KeyFailureKind.SERVER, start + 20_000)
        assertNull(pool.select(start + 20_000 + 29_999))
        assertNotNull(pool.select(start + 20_000 + 30_000))

        pool = pool.recordFailure("key-1", KeyFailureKind.NETWORK, start + 60_000)
        assertNull(pool.select(start + 60_000 + 59_999))
        assertNotNull(pool.select(start + 60_000 + 60_000))

        pool = pool.recordFailure("key-1", KeyFailureKind.SERVER, start + 200_000)
        assertNull(pool.select(start + 200_000 + 59_999))
        assertNotNull(pool.select(start + 200_000 + 60_000))

        val recovered = pool.recordSuccess("key-1")
        assertEquals(0, recovered.entries.single().consecutiveFailures)
        assertNotNull(recovered.select(start))
        assertEquals(start, recovered.nextAvailableAt(start)!!)
    }

    @Test
    fun retryAfterCanExtendButNeverShortenCooldown() {
        val start = 10_000L
        val extended = KeyPool.of(listOf("a"))
            .recordFailure("key-1", KeyFailureKind.RATE_LIMIT, start, retryAfterMillis = 90_000)
        assertNull(extended.select(start + 89_999))
        assertNotNull(extended.select(start + 90_000))
        assertEquals(start + 90_000, extended.nextAvailableAt(start)!!)

        val notShortened = KeyPool.of(listOf("a"))
            .recordFailure("key-1", KeyFailureKind.RATE_LIMIT, start, retryAfterMillis = 1_000)
        assertNull(notShortened.select(start + 14_999))
        assertNotNull(notShortened.select(start + 15_000))
    }

    @Test
    fun authenticationFailureDisablesTheKeyAndExplicitEnableStartsClean() {
        val pool = KeyPool.of(listOf("a", "b"))
            .recordFailure("key-1", KeyFailureKind.AUTHENTICATION, 5_000)
        assertEquals(1, pool.enabledCount)
        assertFalse(pool.entries.first().enabled)
        assertEquals(KeyFailureKind.AUTHENTICATION, pool.entries.first().lastFailure)
        assertNull(pool.setEnabled("key-2", false).nextAvailableAt(5_000))
        assertEquals("key-2", pool.select(5_000)!!.entry.id)

        val restored = pool.setEnabled("key-1", true)
        assertTrue(restored.entries.first().enabled)
        assertEquals(0, restored.entries.first().cooldownUntilMillis)
        assertEquals(0, restored.entries.first().consecutiveFailures)
        assertNull(restored.entries.first().lastFailure)
    }

    @Test
    fun failureClassificationOnlyIncludesCredentialFailures() {
        assertEquals(KeyFailureKind.AUTHENTICATION, KeyFailureKind.classify(ModelFailureKind.AUTHENTICATION))
        assertEquals(KeyFailureKind.RATE_LIMIT, KeyFailureKind.classify(ModelFailureKind.RATE_LIMIT))
        assertEquals(KeyFailureKind.SERVER, KeyFailureKind.classify(ModelFailureKind.SERVER))
        assertEquals(KeyFailureKind.NETWORK, KeyFailureKind.classify(ModelFailureKind.NETWORK))
        assertEquals(KeyFailureKind.NETWORK, KeyFailureKind.classify(ModelFailureKind.TIMEOUT))
        assertNull(KeyFailureKind.classify(ModelFailureKind.PARSING))
        assertNull(KeyFailureKind.classify(ModelFailureKind.CONTENT_FILTERED))
        assertNull(KeyFailureKind.classify(ModelFailureKind.CONTEXT_OVERFLOW))
    }

    @Test
    fun retryAfterParsesSecondsAndHttpDateWithBounds() {
        assertEquals(30_000L, RetryAfterHeader.parseMillis("30", 0))
        assertEquals(RetryAfterHeader.MAX_DELAY_MILLIS, RetryAfterHeader.parseMillis("999999999", 0))
        assertNull(RetryAfterHeader.parseMillis("0", 0))
        assertNull(RetryAfterHeader.parseMillis("-1", 0))
        assertNull(RetryAfterHeader.parseMillis("later", 0))
        assertNull(RetryAfterHeader.parseMillis(null, 0))

        val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }
        val now = 1_600_000_000_000L
        assertEquals(60_000L, RetryAfterHeader.parseMillis(format.format(Date(now + 60_000)), now))
        assertNull(RetryAfterHeader.parseMillis(format.format(Date(now - 60_000)), now))
        assertEquals(RetryAfterHeader.MAX_DELAY_MILLIS,
            RetryAfterHeader.parseMillis(format.format(Date(now + RetryAfterHeader.MAX_DELAY_MILLIS * 2)), now))
    }

    @Test
    fun unknownKeyDoesNotLeakCredential() {
        val pool = KeyPool.of(listOf("very-secret"))
        try {
            pool.recordFailure("missing", KeyFailureKind.NETWORK, 0)
            throw AssertionError("Accepted unknown key")
        } catch (error: IllegalArgumentException) {
            assertFalse(error.message.orEmpty().contains("very-secret"))
        }
    }
}
