package dev.amrakshay.shutterlink.transport

import android.os.Build
import dev.amrakshay.shutterlink.protocol.Message
import dev.amrakshay.shutterlink.protocol.Role
import dev.amrakshay.shutterlink.protocol.isCompatible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What this phone's name looks like to the other one. */
fun thisPhoneName(): String = listOfNotNull(
    Build.MANUFACTURER?.replaceFirstChar { it.uppercase() }?.takeIf { it.isNotBlank() },
    Build.MODEL?.takeIf { it.isNotBlank() },
).joinToString(" ").ifBlank { "Android phone" }

/** Everything the pairing screens need to draw themselves. */
data class PairingState(
    val peers: List<Peer> = emptyList(),
    val pending: Pair<Peer, String>? = null,
    val connected: Peer? = null,
    val theirCapabilities: Message.Capabilities? = null,
    val latencyMillis: Long? = null,
    val video: java.io.InputStream? = null,
    /** -1 while the size is unknown, 100 when done, null when nothing is arriving. */
    val photoProgress: Int? = null,
    val arrivedPhoto: java.io.File? = null,
    val problem: String? = null,
    val incompatible: Boolean = false,
)

/**
 * Drives a [Transport] through finding, confirming and holding a connection,
 * and runs the opening handshake.
 *
 * Kept out of the UI because both roles share it — the camera advertises and the
 * controller discovers, but from "connected" onwards they do exactly the same
 * things, and duplicating that in two screens is how the two sides drift apart.
 */
class Pairing(
    private val transport: Transport,
    private val role: Role,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(PairingState())
    val state: StateFlow<PairingState> = _state.asStateFlow()

    /**
     * Control messages as they arrive, for whichever screen is acting on them.
     * Separate from [state] because these are events — acting on the same
     * Capture twice because a recomposition replayed it would take two photos.
     */
    private val _incoming = MutableSharedFlow<Message>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val incoming: SharedFlow<Message> = _incoming.asSharedFlow()

    /** Send a control message to the other phone. */
    fun send(message: Message) = transport.send(message)

    /** Camera phone: open the video channel. */
    fun startVideo() = transport.startVideo()

    fun stopVideo() = transport.stopVideo()

    /** Camera phone: start sending a captured photo. Returns its payload id. */
    fun startPhotoTransfer(file: java.io.File): Long? = transport.sendPhoto(file)

    private val myName = thisPhoneName()

    fun start() {
        scope.launch { transport.events.collect(::onEvent) }
        when (role) {
            Role.CAMERA -> transport.advertise(myName)
            Role.CONTROLLER -> transport.discover()
        }
    }

    fun connectTo(peer: Peer) = transport.requestConnection(myName, peer)

    fun confirm() {
        _state.value.pending?.let { (peer, _) -> transport.accept(peer.id) }
    }

    fun deny() {
        _state.value.pending?.let { (peer, _) -> transport.reject(peer.id) }
        _state.update { it.copy(pending = null) }
    }

    fun stop() = transport.stop()

    private fun onEvent(event: TransportEvent) {
        when (event) {
            is TransportEvent.PeerFound -> _state.update {
                it.copy(peers = (it.peers.filterNot { p -> p.id == event.peer.id } + event.peer))
            }

            is TransportEvent.PeerLost -> _state.update {
                it.copy(peers = it.peers.filterNot { p -> p.id == event.id })
            }

            is TransportEvent.ConnectionRequested -> _state.update {
                it.copy(pending = event.peer to event.code)
            }

            is TransportEvent.Connected -> {
                _state.update { it.copy(connected = event.peer, pending = null, problem = null) }
                // The controller opens the conversation; whoever speaks first
                // has to be fixed, or both sides wait for the other.
                if (role == Role.CONTROLLER) {
                    transport.send(Message.Hello(deviceName = myName))
                    transport.send(Message.Ping(stamp = System.currentTimeMillis()))
                }
            }

            is TransportEvent.Disconnected -> _state.update {
                it.copy(
                    connected = null,
                    pending = null,
                    theirCapabilities = null,
                    latencyMillis = null,
                    peers = it.peers.filterNot { p -> p.id == event.id },
                )
            }

            is TransportEvent.Received -> {
                onMessage(event.message)
                _incoming.tryEmit(event.message)
            }

            is TransportEvent.VideoAvailable -> _state.update { it.copy(video = event.stream) }

            is TransportEvent.PhotoArriving -> _state.update { it.copy(photoProgress = event.percent) }

            is TransportEvent.PhotoArrived -> _state.update {
                it.copy(photoProgress = null, arrivedPhoto = event.file)
            }

            is TransportEvent.PhotoFailed -> _state.update {
                it.copy(photoProgress = null, problem = "That photo did not come over")
            }

            is TransportEvent.Failed -> _state.update {
                it.copy(problem = "Trouble ${event.doing}: ${event.cause?.message ?: "unknown"}")
            }
        }
    }

    private fun onMessage(message: Message) {
        when (message) {
            is Message.Hello -> {
                if (!isCompatible(message.protocolVersion)) {
                    _state.update { it.copy(incompatible = true) }
                    transport.send(Message.Bye("incompatible protocol"))
                    return
                }
                // Only the camera knows what the camera can do.
                if (role == Role.CAMERA) transport.send(describeThisCamera())
            }

            is Message.Capabilities -> {
                if (!isCompatible(message.protocolVersion)) {
                    _state.update { it.copy(incompatible = true) }
                    return
                }
                _state.update { it.copy(theirCapabilities = message) }
            }

            is Message.Ping -> transport.send(Message.Pong(message.stamp))

            is Message.Pong -> _state.update {
                it.copy(latencyMillis = System.currentTimeMillis() - message.stamp)
            }

            is Message.Bye -> _state.update {
                it.copy(connected = null, problem = "The other phone left: ${message.reason}")
            }

            // Everything below is acted on by whichever screen is in charge, via
            // `incoming`. Listed rather than lumped into an `else` so that
            // adding a message type makes the compiler ask where it belongs —
            // which has already caught two gaps while building this.
            is Message.StartPreview,
            Message.StopPreview,
            is Message.SetZoom,
            is Message.SwitchCamera,
            is Message.Capture,
            is Message.Countdown,
            Message.CaptureStarted,
            is Message.CaptureFailed,
            is Message.PhotoReady,
            is Message.Status,
            is Message.Engines,
            is Message.DeletePhoto,
            -> Unit
        }
    }

    /**
     * Filled in properly in Phase 3, when the camera session is running
     * alongside the transport. Phase 2 only has to prove the round trip.
     */
    private fun describeThisCamera() = Message.Capabilities(
        deviceName = myName,
        hasFrontCamera = true,
        hasBackCamera = true,
        minZoom = 1f,
        maxZoom = 1f,
    )

    /** Measure the link. The controller does this; the camera just answers. */
    fun ping() = transport.send(Message.Ping(stamp = System.currentTimeMillis()))
}
