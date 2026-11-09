package dev.amrakshay.shutterlink.camera

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.Surface
import dev.amrakshay.shutterlink.protocol.FrameCodec
import dev.amrakshay.shutterlink.protocol.VideoFrame
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "ShutterLink/Encoder"

/** What the preview is being sent at. Dropped automatically when the link struggles. */
data class StreamQuality(val width: Int, val height: Int, val fps: Int, val bitrate: Int) {
    companion object {
        val HIGH = StreamQuality(1280, 720, 24, 3_000_000)
        val MEDIUM = StreamQuality(960, 540, 24, 1_600_000)
        val LOW = StreamQuality(640, 360, 15, 700_000)

        /** Worst first, so stepping down is just walking the list. */
        val ladder = listOf(HIGH, MEDIUM, LOW)
    }
}

/**
 * Encodes the camera preview to H.264 and writes it to [sink] as framed packets.
 *
 * The camera feeds the encoder's input Surface directly — CameraX's Preview use
 * case is pointed at it — so no frame is ever copied through the CPU. Going via
 * ImageAnalysis and converting YUV by hand was the alternative and it cannot
 * hold 24fps at 720p on the hardware this targets.
 *
 * The OnePlus 7 this is being built against reports a hardware AVC encoder
 * (`c2.qti.avc.encoder`), which is what makes a real stream possible rather than
 * a slideshow of JPEGs.
 */
class PreviewEncoder(
    private val quality: StreamQuality,
    private val sink: OutputStream,
    private val onBroken: (Throwable) -> Unit,
) {
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private val running = AtomicBoolean(false)
    private var drain: Thread? = null

    /**
     * The decoder's setup bytes (SPS/PPS). Kept so they can be re-sent ahead of
     * every keyframe: MediaCodec only emits them once, but a controller that
     * joins late or drops frames needs them again to start decoding, and without
     * this the picture never recovers.
     */
    private var config: ByteArray? = null

    /** The Surface CameraX should draw into. Valid until [stop]. */
    fun start(): Surface {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            quality.width,
            quality.height,
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, quality.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, quality.fps)
            // A keyframe every second. Longer saves bandwidth but lengthens the
            // black period when the controller joins or recovers, and that delay
            // is the thing people actually notice.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // Baseline: the widest decoder support. This stream is watched on
            // whatever phone the user happens to be holding.
            setInteger(
                MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
            )
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val input = codec.createInputSurface()
        codec.start()

        this.codec = codec
        this.surface = input
        running.set(true)
        drain = Thread(::drainLoop, "shutterlink-encoder").apply { start() }
        return input
    }

    /** Ask for a keyframe now — used when a controller (re)joins. */
    fun requestKeyframe() {
        runCatching {
            codec?.setParameters(
                Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) },
            )
        }
    }

    /** Change the bitrate without restarting the stream. */
    fun setBitrate(bitsPerSecond: Int) {
        runCatching {
            codec?.setParameters(
                Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitsPerSecond)
                },
            )
        }
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        try {
            while (running.get()) {
                val codec = this.codec ?: break
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index < 0) continue

                val buffer = codec.getOutputBuffer(index)
                if (buffer != null && info.size > 0) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val bytes = ByteArray(info.size).also { buffer.get(it) }

                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0

                    if (isConfig) {
                        config = bytes
                    }

                    // Re-send the setup bytes in front of every keyframe. This is
                    // what lets a controller that joined late, or lost frames,
                    // pick the picture back up at the next keyframe instead of
                    // staying black until someone reconnects.
                    if (isKey && !isConfig) {
                        config?.let {
                            FrameCodec.write(
                                sink,
                                VideoFrame(info.presentationTimeUs, keyframe = false, config = true, bytes = it),
                            )
                        }
                    }

                    FrameCodec.write(
                        sink,
                        VideoFrame(
                            ptsMicros = info.presentationTimeUs,
                            keyframe = isKey,
                            config = isConfig,
                            bytes = bytes,
                        ),
                    )
                    sink.flush()
                }
                codec.releaseOutputBuffer(index, false)

                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        } catch (t: Throwable) {
            if (running.get()) {
                Log.w(TAG, "encoder stopped", t)
                onBroken(t)
            }
        }
    }

    fun stop() {
        running.set(false)
        drain?.join(500)
        drain = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { surface?.release() }
        codec = null
        surface = null
        config = null
    }
}
