package dev.amrakshay.shutterlink.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectTest {

    @Test
    fun `the first retry is immediate`() {
        // The commonest drop is momentary. Waiting a second to find that out is
        // a second of black screen for nothing.
        assertEquals(0L, Reconnect().nextDelayMillis())
    }

    @Test
    fun `it backs off after the first attempt`() {
        val r = Reconnect(baseDelayMillis = 1000, maxDelayMillis = 8000)
        val delays = buildList {
            repeat(5) {
                add(r.nextDelayMillis())
                r.attempted()
            }
        }
        assertEquals(listOf(0L, 1000L, 2000L, 4000L, 8000L), delays)
    }

    @Test
    fun `the delay is capped`() {
        val r = Reconnect(maxAttempts = 50, baseDelayMillis = 1000, maxDelayMillis = 8000)
        repeat(20) { r.attempted() }
        assertEquals(8000L, r.nextDelayMillis())
    }

    @Test
    fun `the delay never goes negative, at any attempt count`() {
        // `shl` on a Long wraps its count at 64, so some attempt counts overflow
        // into a negative delay — which would be handed back as "retry
        // immediately" and spin. Checking one arbitrary count missed this: the
        // first version of this test picked 80, which happens to land safely.
        // Every count up to 200 is checked instead.
        for (n in 0..200) {
            val r = Reconnect(maxAttempts = 1000, baseDelayMillis = 1000, maxDelayMillis = 8000)
            repeat(n) { r.attempted() }
            val delay = r.nextDelayMillis()
            assertTrue("attempt $n produced a delay of $delay", delay in 0..8000)
        }
    }

    @Test
    fun `the default gives up on time, not after a handful of tries`() {
        // The real budget is wall-clock. Five attempts looked reasonable and
        // ran out in ~25s on the phones, before anyone could walk out of a
        // room and back. This pins the window rather than the attempt count,
        // so tuning the backoff cannot silently shrink it again.
        val r = Reconnect()
        var total = 0L
        while (!r.giveUp) {
            total += r.nextDelayMillis()
            r.attempted()
        }
        assertTrue(
            "the retry window should be at least 90s, was ${total / 1000}s",
            total >= 90_000,
        )
    }

    @Test
    fun `it gives up after the configured number of attempts`() {
        val r = Reconnect(maxAttempts = 3)
        assertFalse(r.giveUp)
        r.attempted(); assertFalse(r.giveUp)
        r.attempted(); assertFalse(r.giveUp)
        r.attempted(); assertTrue(r.giveUp)
    }

    @Test
    fun `success resets the backoff`() {
        // Otherwise a long shoot with occasional drops slowly becomes a shoot
        // that will not reconnect at all.
        val r = Reconnect()
        repeat(3) { r.attempted() }
        r.succeeded()
        assertEquals(0, r.attempts)
        assertEquals(0L, r.nextDelayMillis())
        assertFalse(r.giveUp)
    }

    @Test
    fun `what the user is told counts attempts from one, not zero`() {
        val r = Reconnect(maxAttempts = 5)
        assertEquals("Reconnecting…", r.describe())
        r.attempted()
        assertEquals("Reconnecting — attempt 2 of 5", r.describe())
        repeat(4) { r.attempted() }
        assertEquals("Could not get back to the other phone", r.describe())
    }
}
