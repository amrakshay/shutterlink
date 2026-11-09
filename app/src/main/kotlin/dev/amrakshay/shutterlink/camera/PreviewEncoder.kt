package dev.amrakshay.shutterlink.camera

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
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
        // Try every AVC encoder this phone has, hardware first, until one
        // actually configures.
        //
        // Not defensive programming for its own sake: on the OnePlus 7 the
        // encoder `createEncoderByType` picks — the legacy OMX one — refuses a
        // surface configuration outright ("Unsupported eColorFormat 0x7f000789,
        // configureCodec returning error -38") while the Codec2 encoder on the
        // same phone takes it. Asking for a MIME type and hoping is not enough
        // when two phones in the test pair already disagree.
        val candidates = encoderNames()
        var lastFailure: Throwable? = null

        for (name in candidates) {
            val codec = runCatching { MediaCodec.createByCodecName(name) }.getOrNull() ?: continue
            try {
                codec.configure(format(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val input = codec.createInputSurface()
                codec.start()

                this.codec = codec
                this.surface = input
                running.set(true)
                drain = Thread(::drainLoop, "shutterlink-encoder").apply { start() }
                Log.i(TAG, "encoding with $name at ${quality.width}x${quality.height}")
                return input
            } catch (t: Throwable) {
                Log.w(TAG, "$name would not configure", t)
                lastFailure = t
                runCatching { codec.release() }
            }
        }
        throw IllegalStateException(
            "no H.264 encoder on this phone would accept a surface " +
                "(tried ${candidates.joinToString()})",
            lastFailure,
        )
    }

    private fun format() = MediaFormat.createVideoFormat(
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
        // black period when the controller joins or recovers, and that delay is
        // the thing people actually notice.
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        // KEY_PROFILE is deliberately NOT set. Requesting a profile without a
        // matching KEY_LEVEL makes several Qualcomm OMX encoders fail to
        // configure, and the default these encoders choose is already baseline
        // or better. Pinning the profile bought nothing and cost the stream.
    }

    /** AVC encoders on this phone, hardware ones first. */
    private fun encoderNames(): List<String> {
        val all = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder }
            .filter { it.supportedTypes.any { type -> type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
            .filter {
                runCatching {
                    it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                        .colorFormats
                        .contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                }.getOrDefault(false)
            }
        // Hardware first: software AVC encoding at 720p24 is not realistic here,
        // but it is better than no preview at all, so it stays as a last resort.
        val (hardware, software) = all.partition { it.isHardwareAccelerated }
        return (hardware + software).map { it.name }
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
