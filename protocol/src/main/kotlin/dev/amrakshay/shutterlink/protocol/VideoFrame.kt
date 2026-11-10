package dev.amrakshay.shutterlink.protocol

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * One encoded video frame on its way from the camera phone to the controller.
 *
 * [config] frames carry the decoder's setup data (SPS/PPS for H.264) rather than
 * picture. They are sent before the first picture and again with every keyframe,
 * so a controller that joins late — or that lost a few frames — can start
 * decoding at the next keyframe instead of waiting for a reconnect.
 */
data class VideoFrame(
    val ptsMicros: Long,
    val keyframe: Boolean,
    val config: Boolean,
    val bytes: ByteArray,
    /**
     * The sender's wall clock when this frame left the encoder.
     *
     * Not the same as [ptsMicros], which is the codec's own timebase and means
     * nothing on the other phone. Paired with the clock offset from the ping
     * exchange, this is what turns "it feels like two seconds" into a number.
     */
    val sentAtMillis: Long = 0,
) {
    // Generated equals/hashCode would compare the ByteArray by identity, which
    // silently makes every round-trip test pass. Written out so they compare
    // contents.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VideoFrame) return false
        return ptsMicros == other.ptsMicros &&
            keyframe == other.keyframe &&
            config == other.config &&
            sentAtMillis == other.sentAtMillis &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = ptsMicros.hashCode()
        result = 31 * result + keyframe.hashCode()
        result = 31 * result + config.hashCode()
        result = 31 * result + sentAtMillis.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

/**
 * The frame framing used on the video stream.
 *
 * A Nearby stream payload is a plain byte pipe with no message boundaries, so
 * the boundaries have to be written in. Each frame is:
 *
 * ```
 *   magic   4 bytes   "SLV2" — resynchronisation point and a version
 *   flags   1 byte    bit 0 keyframe, bit 1 config
 *   pts     8 bytes   presentation timestamp, microseconds, big-endian
 *   sent    8 bytes   sender's wall clock in millis, big-endian
 *   length  4 bytes   payload length, big-endian
 *   payload length bytes
 * ```
 *
 * The magic is not redundant: without it, one corrupted length turns the rest of
 * the stream into noise with no way back. With it, a reader that loses its place
 * can scan forward to the next frame.
 */
object FrameCodec {

    // Bumped to SLV2 when the wall-clock field was added. The magic carries the
    // version precisely so a mismatched pair fails to sync rather than
    // misreading each other's fields as picture data.
    val MAGIC = byteArrayOf('S'.code.toByte(), 'L'.code.toByte(), 'V'.code.toByte(), '2'.code.toByte())

    const val HEADER_BYTES = 4 + 1 + 8 + 8 + 4

    /**
     * Frames larger than this are treated as a corrupt length rather than
     * believed. A 720p keyframe is tens of kilobytes; a megabyte is already far
     * past anything legitimate, and allocating what a corrupt 2 GB length asks
     * for would take the app out.
     */
    const val MAX_FRAME_BYTES = 4 * 1024 * 1024

    private const val FLAG_KEYFRAME = 1
    private const val FLAG_CONFIG = 2

    fun write(out: OutputStream, frame: VideoFrame) {
        require(frame.bytes.size <= MAX_FRAME_BYTES) {
            "frame of ${frame.bytes.size} bytes is beyond MAX_FRAME_BYTES"
        }
        val header = ByteArray(HEADER_BYTES)
        MAGIC.copyInto(header, 0)
        header[4] = (
            (if (frame.keyframe) FLAG_KEYFRAME else 0) or
                (if (frame.config) FLAG_CONFIG else 0)
            ).toByte()
        writeLong(header, 5, frame.ptsMicros)
        writeLong(header, 13, frame.sentAtMillis)
        writeInt(header, 21, frame.bytes.size)
        out.write(header)
        out.write(frame.bytes)
    }

    /**
     * Reads the next frame, resynchronising on [MAGIC] if the stream is not
     * sitting on a frame boundary.
     *
     * Returns null at end of stream — which is what a closed connection looks
     * like, and is not an error worth throwing over.
     */
    @Throws(IOException::class)
    fun read(inp: InputStream): VideoFrame? {
        if (!seekMagic(inp)) return null

        val rest = ByteArray(HEADER_BYTES - MAGIC.size)
        if (!readFully(inp, rest)) return null

        val flags = rest[0].toInt()
        val pts = readLong(rest, 1)
        val sentAt = readLong(rest, 9)
        val length = readInt(rest, 17)

        if (length < 0 || length > MAX_FRAME_BYTES) {
            // Treat an impossible length as corruption rather than trusting it.
            // Returning null ends this stream; the caller reconnects, which is
            // far better than allocating whatever the number happened to say.
            return null
        }

        val payload = ByteArray(length)
        if (!readFully(inp, payload)) return null

        return VideoFrame(
            ptsMicros = pts,
            keyframe = flags and FLAG_KEYFRAME != 0,
            config = flags and FLAG_CONFIG != 0,
            bytes = payload,
            sentAtMillis = sentAt,
        )
    }

    /** Scans until the four magic bytes have been read in order. */
    private fun seekMagic(inp: InputStream): Boolean {
        var matched = 0
        while (matched < MAGIC.size) {
            val b = inp.read()
            if (b == -1) return false
            matched = when {
                b.toByte() == MAGIC[matched] -> matched + 1
                // A mismatch may still be the start of the magic: "SSLV1".
                b.toByte() == MAGIC[0] -> 1
                else -> 0
            }
        }
        return true
    }

    private fun readFully(inp: InputStream, into: ByteArray): Boolean {
        var off = 0
        while (off < into.size) {
            val n = try {
                inp.read(into, off, into.size - off)
            } catch (_: EOFException) {
                return false
            }
            if (n == -1) return false
            off += n
        }
        return true
    }

    private fun writeInt(into: ByteArray, at: Int, value: Int) {
        into[at] = (value ushr 24).toByte()
        into[at + 1] = (value ushr 16).toByte()
        into[at + 2] = (value ushr 8).toByte()
        into[at + 3] = value.toByte()
    }

    private fun writeLong(into: ByteArray, at: Int, value: Long) {
        for (i in 0 until 8) into[at + i] = (value ushr (56 - i * 8)).toByte()
    }

    private fun readInt(from: ByteArray, at: Int): Int =
        (from[at].toInt() and 0xFF shl 24) or
            (from[at + 1].toInt() and 0xFF shl 16) or
            (from[at + 2].toInt() and 0xFF shl 8) or
            (from[at + 3].toInt() and 0xFF)

    private fun readLong(from: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (from[at + i].toLong() and 0xFF)
        return v
    }
}
