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
    /**
     * Smoothed end-to-end lag. Smoothed because a single frame's reading is
     * noisy and quality should not be yanked around by one late packet; the
     * trend is what matters.
     */
    val lagMillis: Long = 0,
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
    /** How far the sender's clock is ahead of ours; see Pairing. */
    private val clockOffsetMillis: () -> Long = { 0 },
    private val onHealth: (StreamHealth) -> Unit = {},
    private val onEnded: () -> Unit = {},
    /** The real picture size, taken from the stream rather than assumed. */
    private val onSize: (Int, Int) -> Unit = { _, _ -> },
) {
    private var codec: MediaCodec? = null
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    private val shown = AtomicLong(0)
    private val dropped = AtomicLong(0)

    /** Timing for the slow-start investigation (VERIFY.md §5b). */
    private var openedAtMillis = 0L
    private var firstRenderLogged = false
    private var polled = 0

    @Volatile
    private var lastLagMillis = 0L

    /** True while deliberately discarding late pictures to catch up. */
    private var shedding = false
    private var lastRenderedAtMillis = 0L

    fun start(raw: InputStream) {
        // Buffered, because FrameCodec resynchronises by reading one byte at a
        // time and then pulls the header in small chunks. Against an unbuffered
        // transport stream that is several syscalls per frame, thirty times a
        // second, on the slowest phone in the pair.
        val stream = java.io.BufferedInputStream(raw, 64 * 1024)
        openedAtMillis = System.currentTimeMillis()
        firstRenderLogged = false
        Log.i(TAG, "TIMING stream opened")
        running.set(true)
        worker = Thread({ readLoop(stream) }, "shutterlink-decoder").apply { start() }
    }

    private fun readLoop(stream: InputStream) {
        try {
            while (running.get()) {
                // How much encoded video is sitting unread behind this frame.
                // A backlog here means the delay is a queue on our side, which
                // we can drain; near zero means the wait is inside Nearby's
                // transport and no amount of reading faster will help.
                if (polled++ % 60 == 0) {
                    val waiting = runCatching { stream.available() }.getOrDefault(-1)
                    Log.i(
                        TAG,
                        "TIMING backlog ${waiting} bytes unread, " +
                            "lag ${lastLagMillis}ms (clock offset ${clockOffsetMillis()}ms)",
                    )
                }

                val frame = FrameCodec.read(stream) ?: break

                // End to end, encoder to here, with the clocks aligned. This is
                // the number that matters and the one nothing measured until
                // now — frame counters and round trips both read healthy while
                // the picture was two seconds behind.
                if (frame.sentAtMillis > 0) {
                    // MINUS the offset, not plus. The offset is how far the
                    // sender's clock is AHEAD of ours, so converting its
                    // timestamp into our clock subtracts it. Adding it doubled
                    // the error instead of cancelling it and produced a lag of
                    // minus five seconds — which is at least an impossible
                    // number rather than a plausible wrong one.
                    val sample =
                        System.currentTimeMillis() - (frame.sentAtMillis - clockOffsetMillis())
                    // Rises quickly, falls slowly: being late matters more than
                    // briefly being on time, and quality should drop fast and
                    // recover cautiously.
                    lastLagMillis = if (sample > lastLagMillis) {
                        (lastLagMillis * 3 + sample * 7) / 10
                    } else {
                        (lastLagMillis * 8 + sample * 2) / 10
                    }
                }

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
        Log.i(
            TAG,
            "TIMING decoder configured ${System.currentTimeMillis() - openedAtMillis}ms " +
                "after the stream opened, from ${frame.bytes.size} setup bytes " +
                "(${dropped.get()} frames dropped waiting for it)",
        )
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
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // The only trustworthy source for the picture size: it comes
                // from the stream's own SPS, not from what the sender said it
                // would send.
                val out = codec.outputFormat
                runCatching {
                    onSize(
                        out.getInteger(MediaFormat.KEY_WIDTH),
                        out.getInteger(MediaFormat.KEY_HEIGHT),
                    )
                }
                continue
            }
            if (index < 0) break
            // Decode every frame — the decoder needs them all for its
            // reference frames — but only DISPLAY the ones that are still
            // current. Discarding a late picture without rendering drains the
            // backlog far faster than real time, so the feed catches up with
            // reality instead of staying behind it forever.
            //
            // Hysteresis on purpose: start shedding above 400ms, stop below
            // 150ms, so it does not flicker in and out around one threshold.
            if (shedding && lastLagMillis < 150) shedding = false
            if (!shedding && lastLagMillis > 400) {
                shedding = true
                Log.i(TAG, "TIMING ${lastLagMillis}ms behind — catching up")
            }

            // Shedding must never become a frozen screen. Discarding late
            // pictures only helps when there is a backlog to drain; if the link
            // itself has collapsed, nothing arrives to catch up ON, and
            // discarding what little does arrive leaves a still image while the
            // app insists it is connected. Observed exactly that: 42 seconds
            // behind, frame counter stuck, picture frozen.
            //
            // So a frame is always shown at least every half second. A late
            // picture is poor; a frozen one is a broken app.
            val now = System.currentTimeMillis()
            val starved = now - lastRenderedAtMillis > 500
            if (shedding && !starved) {
                codec.releaseOutputBuffer(index, false)
                dropped.incrementAndGet()
                continue
            }
            lastRenderedAtMillis = now
            codec.releaseOutputBuffer(index, true)
            if (!firstRenderLogged) {
                firstRenderLogged = true
                Log.i(
                    TAG,
                    "TIMING first picture ${System.currentTimeMillis() - openedAtMillis}ms " +
                        "after the stream opened",
                )
            }
            onHealth(
                StreamHealth(
                    framesShown = shown.incrementAndGet(),
                    framesDropped = dropped.get(),
                    lastFrameAtMillis = System.currentTimeMillis(),
                    lagMillis = lastLagMillis,
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
