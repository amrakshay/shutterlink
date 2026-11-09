package dev.amrakshay.shutterlink.transport

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import dev.amrakshay.shutterlink.protocol.Message
import dev.amrakshay.shutterlink.protocol.MessageCodec
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

private const val TAG = "ShutterLink/Transport"

/**
 * The service id both phones must agree on to see each other. It is the package
 * name so two unrelated Nearby apps on the same phone cannot collide, and it is
 * part of the wire contract — changing it stops old installs finding new ones.
 */
private const val SERVICE_ID = "dev.amrakshay.shutterlink"

/**
 * [Transport] over Google's Nearby Connections.
 *
 * `P2P_POINT_TO_POINT` rather than the star strategies: it is the only one that
 * asks for a high-bandwidth link up front, which is what the preview needs. The
 * others would connect happily and then be too slow to stream, which is a far
 * worse failure than refusing to connect.
 */
class NearbyTransport(context: Context) : Transport {

    private val client = Nearby.getConnectionsClient(context.applicationContext)

    // A replay of 0 with a generous buffer: a late collector should not be shown
    // a stale "connected" for a link that has since dropped, but events arriving
    // while the UI is recomposing must not be lost either.
    private val _events = MutableSharedFlow<TransportEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<TransportEvent> = _events.asSharedFlow()

    /** Endpoints we know a name for, so events can carry one rather than an opaque id. */
    private val names = mutableMapOf<String, String>()
    private var connected: String? = null

    private fun emit(event: TransportEvent) {
        if (!_events.tryEmit(event)) Log.w(TAG, "dropped event $event")
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            names[id] = info.endpointName
            // Nearby's token is longer than anyone will compare on two screens.
            // Four digits is what the mocks show and what people actually check.
            val code = info.authenticationDigits.takeLast(4)
            emit(TransportEvent.ConnectionRequested(Peer(id, info.endpointName), code))
        }

        override fun onConnectionResult(id: String, resolution: ConnectionResolution) {
            when (resolution.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    connected = id
                    // Once paired there is nothing to gain from staying visible,
                    // and staying visible costs battery on both phones.
                    client.stopAdvertising()
                    client.stopDiscovery()
                    emit(TransportEvent.Connected(Peer(id, names[id] ?: id)))
                }

                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED ->
                    emit(TransportEvent.Disconnected(id))

                else -> emit(
                    TransportEvent.Failed(
                        "connecting",
                        IllegalStateException("status ${resolution.status.statusCode}"),
                    ),
                )
            }
        }

        override fun onDisconnected(id: String) {
            if (connected == id) connected = null
            emit(TransportEvent.Disconnected(id))
        }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
            names[id] = info.endpointName
            emit(TransportEvent.PeerFound(Peer(id, info.endpointName)))
        }

        override fun onEndpointLost(id: String) {
            names.remove(id)
            emit(TransportEvent.PeerLost(id))
        }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            // Null here means a message type this build does not know, or a
            // damaged payload. Either way it is dropped rather than fatal —
            // see MessageCodec.
            val message = MessageCodec.decode(bytes) ?: run {
                Log.i(TAG, "ignored an unreadable ${bytes.size}-byte payload")
                return
            }
            emit(TransportEvent.Received(id, message))
        }

        override fun onPayloadTransferUpdate(id: String, update: PayloadTransferUpdate) = Unit
    }

    override fun advertise(name: String) {
        client.startAdvertising(
            name,
            SERVICE_ID,
            lifecycle,
            AdvertisingOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build(),
        )
            .addOnSuccessListener { Log.i(TAG, "advertising as $name") }
            .addOnFailureListener { emit(TransportEvent.Failed("advertising", it)) }
    }

    override fun discover() {
        client.startDiscovery(
            SERVICE_ID,
            discovery,
            DiscoveryOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build(),
        )
            .addOnSuccessListener { Log.i(TAG, "discovering") }
            .addOnFailureListener { emit(TransportEvent.Failed("looking for phones", it)) }
    }

    override fun requestConnection(localName: String, peer: Peer) {
        names[peer.id] = peer.name
        client.requestConnection(localName, peer.id, lifecycle)
            .addOnFailureListener { emit(TransportEvent.Failed("connecting", it)) }
    }

    override fun accept(id: String) {
        client.acceptConnection(id, payloads)
            .addOnFailureListener { emit(TransportEvent.Failed("accepting", it)) }
    }

    override fun reject(id: String) {
        client.rejectConnection(id)
    }

    override fun send(message: Message) {
        val to = connected ?: return
        client.sendPayload(to, Payload.fromBytes(MessageCodec.encode(message)))
            .addOnFailureListener { emit(TransportEvent.Failed("sending", it)) }
    }

    override fun stop() {
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        connected = null
        names.clear()
    }
}
