package dev.amrakshay.shutterlink.transport

import dev.amrakshay.shutterlink.protocol.Message
import kotlinx.coroutines.flow.Flow

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
    data class Disconnected(val id: String) : TransportEvent
    data class Received(val from: String, val message: Message) : TransportEvent

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

    /** Stop everything: advertising, discovery and any live connection. */
    fun stop()
}
