package dev.amrakshay.shutterlink.camera

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import dev.amrakshay.shutterlink.protocol.FrameCodec
import dev.amrakshay.shutterlink.protocol.VideoFrame
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "ShutterLink/Decoder"

/** What the controller can tell the user about the link, without guessing. */
data class StreamHealth(
    val framesShown: Long = 0,
    val framesDropped: Long = 0,
    val lastFrameAtMillis: Long = 0,
)

/**
 * Reads framed H.264 off the stream and draws it on [surface].
 *
 * Deliberately forgiving: a frame that arrives before the decoder has its setup
 * bytes is dropped rather than treated as a failure, because that is the normal
 * state of affairs for the first fraction of a second after connecting, and
 * because recovering at the next keyframe is always better than tearing the
 * connection down.
 */
class VideoDecoder(
    private val surface: Surface,
    private val onHealth: (StreamHealth) -> Unit = {},
    private val onEnded: () -> Unit = {},
) {
    private var codec: MediaCodec? = null
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    private val shown = AtomicLong(0)
    private val dropped = AtomicLong(0)

    fun start(stream: InputStream) {
        running.set(true)
        worker = Thread({ readLoop(stream) }, "shutterlink-decoder").apply { start() }
    }

    private fun readLoop(stream: InputStream) {
        try {
            while (running.get()) {
                val frame = FrameCodec.read(stream) ?: break

                if (frame.config) {
                    // Setup bytes. Configure on the first set; ignore repeats,
                    // which the encoder sends before every keyframe on purpose.
                    if (codec == null) configure(frame)
                    continue
                }

                val codec = this.codec
                if (codec == null) {
                    // Picture before setup. Normal right after connecting —
                    // wait for the keyframe that carries the config with it.
                    dropped.incrementAndGet()
                    continue
                }
                feed(codec, frame)
                render(codec)
            }
        } catch (t: Throwable) {
            if (running.get()) Log.w(TAG, "decoder stopped", t)
        } finally {
            if (running.get()) onEnded()
        }
    }

    private fun configure(frame: VideoFrame) {
        // Dimensions come from the stream's own SPS once decoding starts; the
        // values here only size the initial buffers, and MediaCodec reports the
        // real ones through an output format change.
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(frame.bytes))
        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, surface, null, 0)
            start()
        }
        Log.i(TAG, "decoder configured from ${frame.bytes.size} setup bytes")
    }

    private fun feed(codec: MediaCodec, frame: VideoFrame) {
        val index = codec.dequeueInputBuffer(10_000)
        if (index < 0) {
            // No input buffer free: the decoder is behind. Dropping is right —
            // queueing would turn latency into a growing backlog, and on a
            // viewfinder a late frame is worth nothing.
            dropped.incrementAndGet()
            return
        }
        val buffer = codec.getInputBuffer(index) ?: return
        buffer.clear()
        buffer.put(frame.bytes)
        codec.queueInputBuffer(
            index,
            0,
            frame.bytes.size,
            frame.ptsMicros,
            if (frame.keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0,
        )
    }

    private fun render(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index < 0) break
            // true: hand the buffer to the Surface, which is the whole point.
            codec.releaseOutputBuffer(index, true)
            onHealth(
                StreamHealth(
                    framesShown = shown.incrementAndGet(),
                    framesDropped = dropped.get(),
                    lastFrameAtMillis = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun stop() {
        running.set(false)
        worker?.join(500)
        worker = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
    }
}
