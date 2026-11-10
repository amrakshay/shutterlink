package dev.amrakshay.shutterlink.hid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The HID descriptor and the reports built from it.
 *
 * This is the only pure logic in the app and it is also the riskiest part of
 * it: a report descriptor is a wall of magic numbers that no compiler checks,
 * and a wrong one produces an app that registers successfully, connects
 * successfully, and silently never takes a photo. Every test here corresponds
 * to a way that has actually gone wrong or plausibly could.
 */
class HidKeyboardTest {

    @Test
    fun `the descriptor can express every shutter key`() {
        // The one that matters. A textbook boot-keyboard descriptor declares
        // Logical Maximum 101 (0x65), which covers the printable keys — and
        // Volume Up is usage 0x80, outside it. Copying a standard descriptor
        // gives an app that cannot send the only key it exists to send, with
        // no error anywhere.
        val logicalMax = HidKeyboard.declaredLogicalMaximum()
        val usageMax = HidKeyboard.declaredUsageMaximum()
        for (key in ShutterKey.entries) {
            assertTrue(
                "${key.label} is usage 0x${key.usage.toString(16)}, beyond the " +
                    "descriptor's logical maximum of $logicalMax",
                key.usage <= logicalMax,
            )
            assertTrue(
                "${key.label} is usage 0x${key.usage.toString(16)}, beyond the " +
                    "descriptor's usage maximum of $usageMax",
                key.usage <= usageMax,
            )
        }
    }

    @Test
    fun `the default key is past the range a textbook descriptor declares`() {
        // Pins down WHY the descriptor is non-standard, so that narrowing it
        // back to 0x65 fails here with a reason rather than in someone's hand
        // three weeks later.
        assertTrue(
            "Volume Up should be beyond 0x65 — if it is not, the whole reason " +
                "this descriptor is unusual has gone",
            ShutterKey.VOLUME_UP.usage > 0x65,
        )
    }

    @Test
    fun `a press report carries the usage in the first key slot`() {
        val report = HidKeyboard.pressReport(ShutterKey.VOLUME_UP)
        assertEquals(HidKeyboard.REPORT_BYTES, report.size)
        assertEquals(
            ShutterKey.VOLUME_UP.usage.toByte(),
            report[HidKeyboard.FIRST_KEY_INDEX],
        )
    }

    @Test
    fun `a press report holds no modifiers and nothing in the reserved byte`() {
        val report = HidKeyboard.pressReport(ShutterKey.ENTER)
        assertEquals("modifier byte should be clear", 0, report[0].toInt())
        assertEquals("reserved byte must stay zero", 0, report[1].toInt())
    }

    @Test
    fun `the release report is empty`() {
        // A press with no release leaves the host believing a key is held,
        // which on a camera app means a continuous burst, or a volume slider
        // pinned at maximum.
        assertArrayEquals(ByteArray(HidKeyboard.REPORT_BYTES), HidKeyboard.releaseReport())
    }

    @Test
    fun `each key produces a different report`() {
        val reports = ShutterKey.entries.map { HidKeyboard.pressReport(it).toList() }
        assertEquals(
            "two shutter keys produce the same report, so one of them is unreachable",
            ShutterKey.entries.size,
            reports.toSet().size,
        )
    }

    @Test
    fun `every key usage fits in a single byte`() {
        // A report slot is one byte. A usage above 0xFF would be silently
        // truncated into a different key rather than refused.
        for (key in ShutterKey.entries) {
            assertTrue("${key.label} does not fit in a report byte", key.usage in 0..0xFF)
        }
    }

    @Test
    fun `the descriptor is a well formed sequence of items`() {
        // Walks it the way a host does. A descriptor with a truncated final
        // item reads as valid on a casual look and is rejected by the stack.
        var i = 0
        var items = 0
        while (i < HidKeyboard.REPORT_DESCRIPTOR.size) {
            val prefix = HidKeyboard.REPORT_DESCRIPTOR[i].toInt() and 0xFF
            val size = if (prefix and 0x03 == 3) 4 else prefix and 0x03
            assertTrue(
                "item $items at byte $i claims $size payload bytes but the " +
                    "descriptor ends at ${HidKeyboard.REPORT_DESCRIPTOR.size}",
                i + 1 + size <= HidKeyboard.REPORT_DESCRIPTOR.size,
            )
            i += 1 + size
            items++
        }
        assertEquals("the last item must end exactly at the end", HidKeyboard.REPORT_DESCRIPTOR.size, i)
    }
}
