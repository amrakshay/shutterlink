package dev.amrakshay.shutterlink.hid

/**
 * Keys a camera app might accept as its shutter.
 *
 * A choice rather than a constant, because camera apps disagree. Measured
 * against the OnePlus stock camera: volume up, volume down, camera, enter and
 * D-pad centre all take a photo; headset hook and focus do not. Volume up is
 * the default because it is what commercial selfie remotes send.
 */
enum class ShutterKey(val usage: Int, val label: String) {
    VOLUME_UP(0x80, "Volume up"),
    VOLUME_DOWN(0x81, "Volume down"),
    ENTER(0x28, "Enter"),
}

/**
 * The keyboard this app pretends to be: its report descriptor and its reports.
 *
 * Deliberately free of any Android dependency so it can be tested without a
 * device. This is the part of the app most worth testing and least reachable
 * from one: a descriptor is a wall of magic numbers that no compiler checks,
 * and getting one byte wrong produces an app that registers successfully,
 * connects successfully, and then does nothing at all.
 */
object HidKeyboard {

    /** Boot-keyboard report length: modifiers, reserved, six key slots. */
    const val REPORT_BYTES = 8

    /** Where the first key usage sits in a report. */
    const val FIRST_KEY_INDEX = 2

    /**
     * A boot-keyboard report descriptor with one deliberate change.
     *
     * The usage and logical maximums go to 0xFF rather than the textbook 0x65.
     * 0x65 covers the printable keys, and every descriptor you will find
     * written down stops there — but **Volume Up is usage 0x80**, outside that
     * range. A descriptor copied faithfully cannot express the one key this
     * whole app exists to send, and nothing anywhere reports an error: the
     * report is simply not valid and the shutter does nothing.
     *
     * [HidKeyboardTest] checks this property rather than the byte array, so a
     * reformat is free and a narrowing is caught.
     */
    val REPORT_DESCRIPTOR: ByteArray = byteArrayOf(
        0x05, 0x01, // Usage Page (Generic Desktop)
        0x09, 0x06, // Usage (Keyboard)
        0xA1.toByte(), 0x01, // Collection (Application)
        0x05, 0x07, //   Usage Page (Keyboard)
        0x19, 0xE0.toByte(), //   Usage Minimum (Left Control)
        0x29, 0xE7.toByte(), //   Usage Maximum (Right GUI)
        0x15, 0x00, //   Logical Minimum (0)
        0x25, 0x01, //   Logical Maximum (1)
        0x75, 0x01, //   Report Size (1)
        0x95.toByte(), 0x08, //   Report Count (8)
        0x81.toByte(), 0x02, //   Input (Data, Variable, Absolute) — modifiers
        0x95.toByte(), 0x01, //   Report Count (1)
        0x75, 0x08, //   Report Size (8)
        0x81.toByte(), 0x01, //   Input (Constant) — reserved
        0x95.toByte(), 0x06, //   Report Count (6)
        0x75, 0x08, //   Report Size (8)
        0x15, 0x00, //   Logical Minimum (0)
        0x26, 0xFF.toByte(), 0x00, //   Logical Maximum (255) — reaches Volume Up
        0x05, 0x07, //   Usage Page (Keyboard)
        0x19, 0x00, //   Usage Minimum (0)
        0x29, 0xFF.toByte(), //   Usage Maximum (255)
        0x81.toByte(), 0x00, //   Input (Data, Array)
        0xC0.toByte(), // End Collection
    )

    /** The report that says [key] is held down. */
    fun pressReport(key: ShutterKey): ByteArray =
        ByteArray(REPORT_BYTES).also { it[FIRST_KEY_INDEX] = key.usage.toByte() }

    /**
     * The report that says nothing is held.
     *
     * Not optional. A press with no matching release leaves the host believing
     * a key is stuck down, which on a camera app means a continuous burst or a
     * volume slider pinned at maximum.
     */
    fun releaseReport(): ByteArray = ByteArray(REPORT_BYTES)

    /**
     * The largest key usage this descriptor can actually express.
     *
     * Walks the descriptor's items rather than trusting the comments beside
     * them, which is the whole point: the comments were right in the version
     * that did not work.
     */
    fun declaredUsageMaximum(): Int = globalItems(tag = USAGE_MAXIMUM).maxOrNull() ?: 0

    /** The largest logical value the descriptor permits in a key slot. */
    fun declaredLogicalMaximum(): Int = globalItems(tag = LOGICAL_MAXIMUM).maxOrNull() ?: 0

    private const val LOGICAL_MAXIMUM = 0x24
    private const val USAGE_MAXIMUM = 0x28

    /**
     * Every value declared for one HID item tag.
     *
     * A short-form HID item is a one-byte prefix — tag and type in the upper
     * six bits, payload size in the lower two — followed by that many bytes of
     * little-endian payload.
     */
    private fun globalItems(tag: Int): List<Int> {
        val found = mutableListOf<Int>()
        var i = 0
        while (i < REPORT_DESCRIPTOR.size) {
            val prefix = REPORT_DESCRIPTOR[i].toInt() and 0xFF
            val size = when (prefix and 0x03) {
                3 -> 4
                else -> prefix and 0x03
            }
            if (prefix and 0xFC == tag) {
                var value = 0
                for (b in 0 until size) {
                    value = value or
                        ((REPORT_DESCRIPTOR[i + 1 + b].toInt() and 0xFF) shl (8 * b))
                }
                found += value
            }
            i += 1 + size
        }
        return found
    }
}
