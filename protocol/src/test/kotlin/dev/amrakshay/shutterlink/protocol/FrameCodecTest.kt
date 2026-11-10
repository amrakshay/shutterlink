package dev.amrakshay.shutterlink.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class FrameCodecTest {

    private fun frame(
        pts: Long = 0,
        keyframe: Boolean = false,
        config: Boolean = false,
        size: Int = 32,
        fill: Byte = 7,
        sentAt: Long = 1_762_700_000_000L,
    ) = VideoFrame(pts, keyframe, config, ByteArray(size) { fill }, sentAt)

    private fun roundTrip(vararg frames: VideoFrame): List<VideoFrame> {
        val out = ByteArrayOutputStream()
        frames.forEach { FrameCodec.write(out, it) }
        val inp = ByteArrayInputStream(out.toByteArray())
        return generateSequence { FrameCodec.read(inp) }.toList()
    }

    @Test
    fun `a frame survives the round trip`() {
        val sent = frame(pts = 123_456, keyframe = true, config = false)
        assertEquals(listOf(sent), roundTrip(sent))
    }

    @Test
    fun `flags are not confused with each other`() {
        val config = frame(config = true, keyframe = false)
        val key = frame(config = false, keyframe = true)
        val plain = frame(config = false, keyframe = false)
        val both = frame(config = true, keyframe = true)

        val back = roundTrip(config, key, plain, both)
        assertEquals(4, back.size)
        assertTrue(back[0].config && !back[0].keyframe)
        assertTrue(!back[1].config && back[1].keyframe)
        assertTrue(!back[2].config && !back[2].keyframe)
        assertTrue(back[3].config && back[3].keyframe)
    }

    @Test
    fun `many frames come back in order`() {
        val sent = (1..50).map { frame(pts = it * 33_366L, size = it, fill = it.toByte()) }
        val back = roundTrip(*sent.toTypedArray())
        assertEquals(sent, back)
    }

    @Test
    fun `a large timestamp is not truncated`() {
        // Microseconds since boot overflows an Int in about 35 minutes, which is
        // well within one shoot, so the field has to be a Long end to end.
        val sent = frame(pts = 9_000_000_000L)
        assertEquals(9_000_000_000L, roundTrip(sent).single().ptsMicros)
    }

    @Test
    fun `an empty payload is legal`() {
        val sent = frame(size = 0)
        val back = roundTrip(sent).single()
        assertArrayEquals(ByteArray(0), back.bytes)
    }

    @Test
    fun `end of stream reads as null rather than throwing`() {
        assertNull(FrameCodec.read(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `a truncated payload reads as null`() {
        val out = ByteArrayOutputStream()
        FrameCodec.write(out, frame(size = 500))
        val half = out.toByteArray().copyOf(FrameCodec.HEADER_BYTES + 100)
        assertNull(FrameCodec.read(ByteArrayInputStream(half)))
    }

    @Test
    fun `a truncated header reads as null`() {
        val out = ByteArrayOutputStream()
        FrameCodec.write(out, frame())
        val stub = out.toByteArray().copyOf(FrameCodec.MAGIC.size + 2)
        assertNull(FrameCodec.read(ByteArrayInputStream(stub)))
    }

    @Test
    fun `a corrupt length is refused instead of allocated`() {
        // The whole point of the cap. Without it this is an OutOfMemoryError
        // triggered by four bad bytes on the wire.
        // Built by corrupting a real header rather than by hand, so this keeps
        // testing the cap instead of breaking whenever the header grows — which
        // is exactly what it did when the wall-clock field was added.
        val out = ByteArrayOutputStream()
        FrameCodec.write(out, frame(size = 8))
        val header = out.toByteArray().copyOf(FrameCodec.HEADER_BYTES)
        val lengthAt = FrameCodec.HEADER_BYTES - 4
        header[lengthAt] = 0x7F
        header[lengthAt + 1] = 0xFF.toByte()
        header[lengthAt + 2] = 0xFF.toByte()
        header[lengthAt + 3] = 0xFF.toByte()
        assertNull(FrameCodec.read(ByteArrayInputStream(header)))
    }

    @Test
    fun `a reader that loses its place resynchronises on the next frame`() {
        val good = frame(pts = 999, keyframe = true, size = 16, fill = 3)
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x11, 0x22, 0x33, 'S'.code.toByte(), 'L'.code.toByte(), 0x44))
        FrameCodec.write(out, good)

        val back = FrameCodec.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(good, back)
    }

    @Test
    fun `a partial magic run does not swallow the real one`() {
        // "SLVSLV1…" — the scanner must not consume the real magic while
        // recovering from the false start.
        val good = frame(pts = 7, size = 4, fill = 9)
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf('S'.code.toByte(), 'L'.code.toByte(), 'V'.code.toByte()))
        FrameCodec.write(out, good)
        assertEquals(good, FrameCodec.read(ByteArrayInputStream(out.toByteArray())))
    }

    @Test
    fun `writing an oversized frame is refused at the source`() {
        val huge = VideoFrame(0, false, false, ByteArray(FrameCodec.MAX_FRAME_BYTES + 1))
        val failed = try {
            FrameCodec.write(ByteArrayOutputStream(), huge); false
        } catch (_: IllegalArgumentException) {
            true
        }
        assertTrue("an oversized frame should be refused before it reaches the wire", failed)
    }

    @Test
    fun `the sender's wall clock survives the round trip`() {
        // The whole point of the field: without it arriving intact, end-to-end
        // latency cannot be measured at the far end at all.
        val sent = frame(sentAt = 1_762_700_123_456L)
        assertEquals(1_762_700_123_456L, roundTrip(sent).single().sentAtMillis)
    }

    @Test
    fun `equality compares payload contents, not identity`() {
        // Guards the hand-written equals: the generated one compares ByteArrays
        // by reference, which would make every round-trip test above pass
        // vacuously.
        assertEquals(frame(size = 8, fill = 1), frame(size = 8, fill = 1))
        assertTrue(frame(size = 8, fill = 1) != frame(size = 8, fill = 2))
    }
}
