package dev.amrakshay.shutterlink.transport

import dev.amrakshay.shutterlink.protocol.Message
import kotlinx.coroutines.flow.Flow
import java.io.InputStream
import java.io.OutputStream

/** A phone we can see but have not connected to yet. */
data class Peer(val id: String, val name: String)

/** Everything that can happen on the link, as it happens. */
sealed interface TransportEvent {
    data class PeerFound(val peer: Peer) : TransportEvent
    data class PeerLost(val id: String) : TransportEvent

    /**
     * Both phones get this before anything is streamed, and both must accept.
     * [code] is shown on each screen so the people holding them can check they
     * match — without it, an unattended phone could be connected to by anyone
     * in range.
     */
    data class ConnectionRequested(val peer: Peer, val code: String) : TransportEvent

    data class Connected(val peer: Peer) : TransportEvent

    /**
     * The link's carrying capacity changed.
     *
     * Nearby starts a connection on Bluetooth and upgrades to Wi-Fi later —
     * measured at 35 seconds on the test pair. Streaming HD before that happens
     * pushes 3 Mbps down a link that cannot carry it, and the viewfinder falls
     * a second behind and stays there. So this is not a detail: it decides what
     * quality may be sent.
     */
    data class LinkQuality(val high: Boolean) : TransportEvent
    data class Disconnected(val id: String) : TransportEvent
    data class Received(val from: String, val message: Message) : TransportEvent

    /**
     * The camera phone has opened its video stream. The controller reads frames
     * off this until it ends, which is also how it learns the stream stopped.
     */
    data class VideoAvailable(val stream: InputStream) : TransportEvent

    /** A photo is on its way. [percent] is -1 while the size is still unknown. */
    data class PhotoArriving(val payloadId: Long, val percent: Int) : TransportEvent

    /**
     * A photo finished transferring.
     *
     * A content Uri, not a File. Nearby drops received files in its own folder
     * under Download, which scoped storage will not let us open by path —
     * `length()` succeeds on it and `open()` fails with EACCES, which makes the
     * failure look like a corrupt image rather than a permission problem.
     */
    data class PhotoArrived(val payloadId: Long, val uri: android.net.Uri) : TransportEvent

    data class PhotoFailed(val payloadId: Long) : TransportEvent

    /** Something went wrong that the user may need to know about. */
    data class Failed(val doing: String, val cause: Throwable?) : TransportEvent
}

/**
 * How the two phones find each other and talk.
 *
 * This interface exists so the implementation can be replaced. The one we ship
 * is Nearby Connections, which is BLE discovery with an automatic Wi-Fi Direct
 * upgrade already assembled and far better tested across OEMs than anything we
 * would write. Removing the Google Play Services dependency by doing it by hand
 * is backlog item T-7 — when that happens it should drop in here, not alongside.
 *
 * So: nothing above this interface may mention Nearby.
 */
interface Transport {

    val events: Flow<TransportEvent>

    /** Camera phone: make this phone findable as [name]. */
    fun advertise(name: String)

    /** Controller phone: start looking for phones that are advertising. */
    fun discover()

    /** Controller phone: ask [peer] to connect. Both sides then get [TransportEvent.ConnectionRequested]. */
    fun requestConnection(localName: String, peer: Peer)

    /** Say yes to a pending connection, once the person has checked the code. */
    fun accept(id: String)

    /** Say no — a code that did not match, or a connection nobody expected. */
    fun reject(id: String)

    fun send(message: Message)

    /**
     * Camera phone: open the video channel and return the sink to write frames
     * into, or null if there is nobody connected.
     *
     * A separate channel from [send] on purpose. Control messages are small and
     * must not queue behind a second of video, and the video is a continuous
     * pipe rather than a series of messages.
     */
    fun startVideo(): OutputStream?

    fun stopVideo()

    /**
     * Camera phone: send a captured photo. Returns the payload id, which goes in
     * the matching [Message.PhotoReady] — Nearby delivers a file with no
     * indication of what it was for, so the two are matched by this.
     */
    fun sendPhoto(file: java.io.File): Long?

    /** Stop everything: advertising, discovery and any live connection. */
    fun stop()
}
