package dev.amrakshay.shutterlink.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Everything the two phones say to each other.
 *
 * Each case carries an explicit [SerialName] rather than relying on the class
 * name, so renaming a class in a later version cannot silently break a phone
 * running an older build. The names are part of the wire format and should be
 * treated as frozen once released.
 */
@Serializable
sealed interface Message {

    /** First thing a controller says. The camera answers with [Capabilities]. */
    @Serializable
    @SerialName("hello")
    data class Hello(
        val protocolVersion: Int = PROTOCOL_VERSION,
        val deviceName: String,
    ) : Message

    /**
     * What the camera phone can actually do, read off the hardware rather than
     * assumed. The controller draws its UI from this — a zoom stop or a flip
     * button that the far phone cannot honour should never appear.
     */
    @Serializable
    @SerialName("capabilities")
    data class Capabilities(
        val protocolVersion: Int = PROTOCOL_VERSION,
        val deviceName: String,
        val hasFrontCamera: Boolean,
        val hasBackCamera: Boolean,
        val minZoom: Float,
        val maxZoom: Float,
        /**
         * How far the controller must rotate the video to show it upright.
         *
         * The camera sensor is landscape and the encoder is fed its buffers
         * untransformed, so a phone propped up in portrait produces frames lying
         * on their side. CameraX knows the angle; only it can, since it depends
         * on the sensor mounting and the phone's current rotation. So it is sent
         * rather than guessed at the other end.
         */
        val rotationDegrees: Int = 0,
    ) : Message

    /**
     * Round-trip timer. The sender's clock is echoed back untouched, so latency
     * is measured without the two phones needing agreeing clocks.
     */
    @Serializable
    @SerialName("ping")
    data class Ping(val stamp: Long) : Message

    @Serializable
    @SerialName("pong")
    data class Pong(val stamp: Long) : Message

    /** Sent when a phone leaves deliberately, so the other can say so rather than guess. */
    @Serializable
    @SerialName("bye")
    data class Bye(val reason: String) : Message

    // ---- Control. The controller asks; the camera does and reports back. ----

    @Serializable
    @SerialName("start_preview")
    data class StartPreview(val width: Int, val height: Int, val fps: Int, val bitrate: Int) : Message

    @Serializable
    @SerialName("stop_preview")
    data object StopPreview : Message

    /** 0..1 across the camera's own range, so the controller never has to know its ratios. */
    @Serializable
    @SerialName("set_zoom")
    data class SetZoom(val linear: Float) : Message

    @Serializable
    @SerialName("switch_camera")
    data class SwitchCamera(val front: Boolean) : Message

    /** [timerSeconds] of 0 fires immediately. */
    @Serializable
    @SerialName("capture")
    data class Capture(val timerSeconds: Int = 0, val engine: String = ENGINE_SHUTTERLINK) : Message

    @Serializable
    @SerialName("countdown")
    data class Countdown(val secondsLeft: Int) : Message

    @Serializable
    @SerialName("capture_started")
    data object CaptureStarted : Message

    @Serializable
    @SerialName("capture_failed")
    data class CaptureFailed(val reason: String) : Message

    /**
     * A photo exists on the camera phone. The bytes follow on the file channel;
     * [payloadId] is how the controller matches the two up, because Nearby
     * delivers a file payload with no indication of what it was for.
     */
    @Serializable
    @SerialName("photo_ready")
    data class PhotoReady(
        val payloadId: Long,
        val name: String,
        val bytes: Long,
        val width: Int,
        val height: Int,
    ) : Message

    /** Periodic, so the controller can warn before the far phone dies or cooks. */
    @Serializable
    @SerialName("status")
    data class Status(
        val batteryPercent: Int,
        val thermal: String,
        val streaming: Boolean,
        val shotsTaken: Int,
    ) : Message

    /** Which camera apps the far phone has, for the capture-engine picker. */
    @Serializable
    @SerialName("engines")
    data class Engines(val engines: List<CaptureEngine>) : Message

    @Serializable
    @SerialName("delete_photo")
    data class DeletePhoto(val name: String) : Message
}

/** ShutterLink's own camera. Always present, always the one with a live preview. */
const val ENGINE_SHUTTERLINK: String = "shutterlink"

/**
 * A camera app on the far phone that the controller may fire.
 *
 * [handsPhotoBack] is the difference between the clean path and the messy one:
 * an app that answers ACTION_IMAGE_CAPTURE returns the photo and control to us
 * by itself, and one that does not needs the gallery watched and an overlay
 * permission to get back. The controller shows neither distinction to the user.
 */
@Serializable
data class CaptureEngine(
    val id: String,
    val label: String,
    val handsPhotoBack: Boolean,
    val tested: Boolean = false,
)
