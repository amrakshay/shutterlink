package dev.amrakshay.shutterlink.protocol

/**
 * How long to wait before trying the connection again, and when to stop.
 *
 * Pulled out of the Android code and made pure so the policy can be tested:
 * backoff is the sort of thing that looks obviously right and turns out to
 * hammer the radio flat or to give up while the user is still walking back into
 * range.
 *
 * The shape is deliberate. The first retry is immediate, because by far the
 * most common drop is a momentary one and waiting two seconds to discover that
 * is two seconds of black screen. After that it backs off, because if three
 * attempts have failed the phones are probably out of range and retrying in a
 * tight loop only drains both batteries.
 */
class Reconnect(
    private val maxAttempts: Int = 5,
    private val firstDelayMillis: Long = 0,
    private val baseDelayMillis: Long = 1_000,
    private val maxDelayMillis: Long = 8_000,
) {
    var attempts: Int = 0
        private set

    /** True while another attempt is worth making. */
    val giveUp: Boolean get() = attempts >= maxAttempts

    /** How long to wait before attempt number [attempts] + 1. */
    fun nextDelayMillis(): Long {
        if (attempts == 0) return firstDelayMillis
        // The shift is clamped and the result floored at zero. `shl` on a Long
        // wraps its count at 64, so a large attempt count silently produces a
        // negative delay — which, coerced only from above, would be handed back
        // as "retry immediately, forever". A caller raising maxAttempts should
        // not be able to turn this into a spin loop.
        val shift = (attempts - 1).coerceIn(0, 32)
        val exponential = baseDelayMillis shl shift
        return exponential.coerceIn(0, maxDelayMillis)
    }

    /** Call when an attempt has been made. */
    fun attempted() {
        attempts += 1
    }

    /** Call when the link is back. */
    fun succeeded() {
        attempts = 0
    }

    /** What to tell the user, in their terms rather than in attempt numbers. */
    fun describe(): String = when {
        giveUp -> "Could not get back to the other phone"
        attempts == 0 -> "Reconnecting…"
        else -> "Reconnecting — attempt ${attempts + 1} of $maxAttempts"
    }
}
