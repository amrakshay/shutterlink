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
}
