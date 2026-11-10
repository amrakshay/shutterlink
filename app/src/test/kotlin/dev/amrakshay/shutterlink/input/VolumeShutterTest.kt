package dev.amrakshay.shutterlink.input

import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeShutterTest {

    private fun decide(
        volume: Boolean = true,
        down: Boolean = true,
        first: Boolean = true,
        canFire: Boolean = true,
    ) = VolumeShutter.decide(volume, down, first, canFire)

    @Test
    fun `a first press fires`() {
        assertEquals(KeyVerdict.FIRE, decide())
    }

    @Test
    fun `the release is swallowed, not fired`() {
        // Swallowed rather than ignored: handing the release back to the system
        // still pops the volume slider up, which is exactly what we are trying
        // to avoid.
        assertEquals(KeyVerdict.SWALLOW, decide(down = false))
    }

    @Test
    fun `holding the key does not machine-gun the shutter`() {
        assertEquals(KeyVerdict.SWALLOW, decide(first = false))
    }

    @Test
    fun `volume keys work normally when nothing is connected`() {
        // The app must not steal the volume keys while it cannot do anything
        // with them.
        assertEquals(KeyVerdict.IGNORE, decide(canFire = false))
        assertEquals(KeyVerdict.IGNORE, decide(canFire = false, down = false))
        assertEquals(KeyVerdict.IGNORE, decide(canFire = false, first = false))
    }

    @Test
    fun `other keys are never touched`() {
        assertEquals(KeyVerdict.IGNORE, decide(volume = false))
        assertEquals(KeyVerdict.IGNORE, decide(volume = false, canFire = false))
    }

    @Test
    fun `every combination resolves to exactly one verdict`() {
        // Cheap guard against someone adding a branch that falls through.
        val seen = mutableSetOf<KeyVerdict>()
        for (v in listOf(true, false)) {
            for (d in listOf(true, false)) {
                for (f in listOf(true, false)) {
                    for (c in listOf(true, false)) {
                        seen += VolumeShutter.decide(v, d, f, c)
                    }
                }
            }
        }
        assertEquals(KeyVerdict.entries.toSet(), seen)
    }
}
