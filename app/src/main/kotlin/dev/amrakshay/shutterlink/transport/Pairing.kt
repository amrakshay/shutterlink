package dev.amrakshay.shutterlink.transport

import android.os.Build
import dev.amrakshay.shutterlink.protocol.Message
import dev.amrakshay.shutterlink.protocol.Role
import dev.amrakshay.shutterlink.protocol.isCompatible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

            is TransportEvent.Received -> onMessage(event.message)

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
