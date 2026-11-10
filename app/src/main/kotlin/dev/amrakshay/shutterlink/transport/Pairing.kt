package dev.amrakshay.shutterlink.transport

import android.os.Build
import dev.amrakshay.shutterlink.protocol.CaptureEngine
import dev.amrakshay.shutterlink.protocol.Message
import dev.amrakshay.shutterlink.protocol.Role
import dev.amrakshay.shutterlink.protocol.Reconnect
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What this phone's name looks like to the other one. */
fun thisPhoneName(): String = listOfNotNull(
    Build.MANUFACTURER?.replaceFirstChar { it.uppercase() }?.takeIf { it.isNotBlank() },
    Build.MODEL?.takeIf { it.isNotBlank() },
).joinToString(" ").ifBlank { "Android phone" }

/**
 * A photo that has come over.
 *
 * The name comes from the camera phone's own [Message.PhotoReady], not from the
 * transport: Nearby names the received file after its payload id, so showing
 * what it hands us puts "-4709986883054979248" on screen where the filename
 * should be.
 */
data class ArrivedPhoto(
    val uri: android.net.Uri,
    val name: String,
    val bytes: Long,
)

/** Everything the pairing screens need to draw themselves. */
data class PairingState(
    val peers: List<Peer> = emptyList(),
    val pending: Pair<Peer, String>? = null,
    val connected: Peer? = null,
    val theirCapabilities: Message.Capabilities? = null,
    val latencyMillis: Long? = null,
    /** -1 while the size is unknown, 100 when done, null when nothing is arriving. */
    val photoProgress: Int? = null,
    val arrivedPhoto: ArrivedPhoto? = null,
    val problem: String? = null,
    val incompatible: Boolean = false,
    val reconnecting: String? = null,
    val engines: List<CaptureEngine> = emptyList(),
    /**
     * False until Nearby has moved the link off Bluetooth onto Wi-Fi.
     *
     * It no longer gates any picture quality — nothing is streamed. It decides
     * what the controller warns about before a shot, because a photo over
     * Bluetooth takes tens of seconds and over Wi-Fi takes about one.
     */
    val linkIsFast: Boolean = false,
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

    /** Done looking at the photo that came over. */
    fun dismissPhoto() {
        _state.update { it.copy(arrivedPhoto = null) }
    }

    /** Camera phone: start sending a captured photo. Returns its payload id. */
    fun startPhotoTransfer(file: java.io.File): Long? = transport.sendPhoto(file)

    private val myName = thisPhoneName()
    private val reconnect = Reconnect()

    /**
     * How far the other phone's clock is ahead of ours, in millis.
     *
     * Needed because a timestamp written on a video frame by the camera phone
     * is meaningless here without it — the two devices have unrelated clocks.
     */
    @Volatile
    var clockOffsetMillis: Long = 0
        private set

    /** The fastest round trip seen; the offset is only taken from one at least this good. */
    private var bestRoundTrip: Long = Long.MAX_VALUE
    private var retryJob: Job? = null

    /** True once this pairing has been connected at least once. */
    private var everConnected = false

    /**
     * The phone we were connected to, so it can be rejoined without making
     * someone walk back and tap through pairing again.
     *
     * Only ever set from a connection the person already approved with the
     * code, and only ever used within this session — see onEvent.
     */
    private var knownPeerName: String? = null

    /** payload id -> what the camera phone called it, from PhotoReady. */
    private val expectedPhotos = mutableMapOf<Long, Message.PhotoReady>()

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
            is TransportEvent.PeerFound -> {
                _state.update {
                    it.copy(peers = (it.peers.filterNot { p -> p.id == event.peer.id } + event.peer))
                }
                // The phone we lost has come back. Rejoin it rather than
                // leaving a Connect button for someone who is standing across
                // the room and cannot reach the screen — which is the entire
                // situation this app is for.
                if (_state.value.connected == null &&
                    role == Role.CONTROLLER &&
                    event.peer.name == knownPeerName
                ) {
                    transport.requestConnection(myName, event.peer)
                }
            }

            is TransportEvent.PeerLost -> _state.update {
                it.copy(peers = it.peers.filterNot { p -> p.id == event.id })
            }

            is TransportEvent.ConnectionRequested -> {
                // Rejoining a phone already approved in this session does not
                // ask for the code again. The check exists so a stranger cannot
                // connect to an unattended phone; it is not served by demanding
                // it again from someone who is standing in the shot. A fresh
                // peer, or a new session, still asks.
                if (everConnected && event.peer.name == knownPeerName) {
                    transport.accept(event.peer.id)
                } else {
                    _state.update { it.copy(pending = event.peer to event.code) }
                }
            }

            is TransportEvent.Connected -> {
                everConnected = true
                knownPeerName = event.peer.name
                retryJob?.cancel()
                retryJob = null
                reconnect.succeeded()
                _state.update {
                    it.copy(
                        connected = event.peer,
                        pending = null,
                        problem = null,
                        reconnecting = null,
                        linkIsFast = false,
                    )
                }
                // The controller opens the conversation; whoever speaks first
                // has to be fixed, or both sides wait for the other.
                if (role == Role.CONTROLLER) {
                    transport.send(Message.Hello(deviceName = myName))
                    transport.send(Message.Ping(stamp = System.currentTimeMillis()))
                }
            }

            is TransportEvent.Disconnected -> {
                _state.update {
                    it.copy(
                        connected = null,
                        pending = null,
                        theirCapabilities = null,
                        latencyMillis = null,
                        peers = it.peers.filterNot { p -> p.id == event.id },
                    )
                }
                // Only chase a connection that existed. A rejected first
                // attempt is the user saying no, and retrying that would be
                // pestering rather than recovering.
                if (everConnected) startRetrying()
            }

            is TransportEvent.Received -> {
                onMessage(event.message)
                _incoming.tryEmit(event.message)
            }

            is TransportEvent.LinkQuality -> _state.update { it.copy(linkIsFast = event.high) }

            is TransportEvent.PhotoArriving -> _state.update { it.copy(photoProgress = event.percent) }

            is TransportEvent.PhotoArrived -> {
                val announced = expectedPhotos.remove(event.payloadId)
                _state.update {
                    it.copy(
                        photoProgress = null,
                        arrivedPhoto = ArrivedPhoto(
                            uri = event.uri,
                            name = announced?.name ?: "Photo",
                            bytes = announced?.bytes ?: 0L,
                        ),
                    )
                }
            }

            is TransportEvent.PhotoFailed -> _state.update {
                it.copy(photoProgress = null, problem = "That photo did not come over")
            }

            is TransportEvent.Failed -> _state.update {
                it.copy(problem = "Trouble ${event.doing}: ${event.cause?.message ?: "unknown"}")
            }
        }
    }

    /**
     * Try to get back, backing off, and say so while it happens.
     *
     * The policy lives in [Reconnect] in the protocol module, where it is
     * tested — backoff is the kind of code that looks obviously right and either
     * hammers the radio flat or gives up while the user is still walking back
     * into range.
     */
    private fun startRetrying() {
        if (retryJob?.isActive == true) return
        retryJob = scope.launch {
            while (!reconnect.giveUp && _state.value.connected == null) {
                _state.update { it.copy(reconnecting = reconnect.describe()) }
                delay(reconnect.nextDelayMillis())
                reconnect.attempted()
                when (role) {
                    Role.CAMERA -> transport.advertise(myName)
                    Role.CONTROLLER -> transport.discover()
                }
                // Give the attempt a chance to land before counting it as lost.
                delay(4_000)
            }
            if (_state.value.connected == null) {
                _state.update { it.copy(reconnecting = reconnect.describe()) }
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

            is Message.Engines -> _state.update { it.copy(engines = message.engines) }

            // Remembered so the file, when it lands, can be shown under the name
            // the camera phone gave it.
            is Message.PhotoReady -> expectedPhotos[message.payloadId] = message

            is Message.Ping -> transport.send(
                Message.Pong(message.stamp, respondedAtMillis = System.currentTimeMillis()),
            )

            is Message.Pong -> {
                val now = System.currentTimeMillis()
                val roundTrip = now - message.stamp
                // Standard one-way estimate: the reply was written at roughly
                // the midpoint of the round trip, so the difference between
                // their clock and ours at that instant is the offset. Good to
                // within the link's asymmetry, which is plenty for telling
                // 200ms of latency from 2000ms.
                // Only trust the offset from the FASTEST round trip seen.
                //
                // The estimate assumes the reply was written at the midpoint of
                // the round trip, which is only true when the link is not
                // congested. Taking every sample made the offset swing by ~700ms
                // between readings — noise larger than the thing being measured.
                // A slow round trip carries no information about the offset; a
                // fast one is nearly all signal. The floor decays slowly so a
                // genuinely improved link is not ignored forever.
                if (message.respondedAtMillis > 0 && roundTrip in 0..bestRoundTrip) {
                    bestRoundTrip = roundTrip
                    clockOffsetMillis = message.respondedAtMillis - (message.stamp + roundTrip / 2)
                }
                bestRoundTrip += 20
                _state.update { it.copy(latencyMillis = roundTrip) }
            }

            is Message.Bye -> _state.update {
                it.copy(connected = null, problem = "The other phone left: ${message.reason}")
            }

            // Everything below is acted on by whichever screen is in charge, via
            // `incoming`. Listed rather than lumped into an `else` so that
            // adding a message type makes the compiler ask where it belongs —
            // which has already caught two gaps while building this.
            is Message.SetZoom,
            is Message.SwitchCamera,
            is Message.Capture,
            is Message.Countdown,
            Message.CaptureStarted,
            is Message.CaptureFailed,
            is Message.Status,
            is Message.DeletePhoto,
            -> Unit
        }
    }

    /**
     * Filled in properly in Phase 3, when the camera session is running
     * alongside the transport. Phase 2 only has to prove the round trip.
     */
    /**
     * The camera phone's real capabilities, once the camera has been bound.
     *
     * This exists because the obvious thing was wrong. [describeThisCamera]
     * used to answer every Hello with a hardcoded `minZoom = 1f, maxZoom = 1f`
     * placeholder, on the assumption that the real figures — which only the
     * bound camera knows — would arrive afterwards and replace it.
     *
     * That assumption held only while binding was slow. Once the preview
     * encoder was removed the camera bound almost immediately, so the real
     * Capabilities frequently went out BEFORE the controller's Hello arrived,
     * and the placeholder answer then overwrote it. The symptom was the zoom
     * slider silently missing, because `maxZoom > minZoom` was false.
     */
    private var realCapabilities: Message.Capabilities? = null

    /**
     * Camera phone: publish what the bound camera can actually do.
     *
     * Goes through here rather than straight to [send] so the answer to a late
     * Hello is the truth rather than a placeholder.
     */
    fun publishCapabilities(capabilities: Message.Capabilities) {
        realCapabilities = capabilities
        transport.send(capabilities)
    }

    private fun describeThisCamera(): Message.Capabilities =
        realCapabilities ?: Message.Capabilities(
            deviceName = myName,
            hasFrontCamera = true,
            hasBackCamera = true,
            // Equal, so the controller hides the zoom slider rather than
            // offering one that does nothing. A control that lies is worse
            // than a control that is missing for a second.
            minZoom = 1f,
            maxZoom = 1f,
        )

    /** Measure the link. The controller does this; the camera just answers. */
    fun ping() = transport.send(Message.Ping(stamp = System.currentTimeMillis()))
}
