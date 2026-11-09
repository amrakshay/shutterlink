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
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

private const val TAG = "ShutterLink/Transport"

/**
 * The service id both phones must agree on to see each other. It is the package
 * name so two unrelated Nearby apps on the same phone cannot collide, and it is
 * part of the wire contract — changing it stops old installs finding new ones.
 */
private const val SERVICE_ID = "dev.amrakshay.shutterlink"

/** Roughly a second of 720p at 3 Mbps, so a brief stall does not block the encoder. */
private const val VIDEO_PIPE_BYTES = 512 * 1024

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
    private var videoSink: PipedOutputStream? = null

    /**
     * File payloads in flight. Nearby hands the payload over as soon as the
     * transfer starts and the bytes only exist once it completes, so the payload
     * has to be held until the matching transfer update says SUCCESS.
     */
    private val arrivingFiles = mutableMapOf<Long, Payload>()

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
            if (payload.type == Payload.Type.FILE) {
                arrivingFiles[payload.id] = payload
                emit(TransportEvent.PhotoArriving(payload.id, -1))
                return
            }
            payload.asStream()?.let { stream ->
                // The video channel. Handed to the caller as a plain InputStream
                // so nothing above Transport has to know about Nearby payloads.
                emit(TransportEvent.VideoAvailable(stream.asInputStream()))
                return
            }
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

        override fun onPayloadTransferUpdate(id: String, update: PayloadTransferUpdate) {
            val pending = arrivingFiles[update.payloadId] ?: return
            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS -> {
                    val total = update.totalBytes
                    val percent =
                        if (total > 0) ((update.bytesTransferred * 100) / total).toInt() else -1
                    emit(TransportEvent.PhotoArriving(update.payloadId, percent))
                }

                PayloadTransferUpdate.Status.SUCCESS -> {
                    arrivingFiles.remove(update.payloadId)
                    // asUri(), never asJavaFile(). The path it returns is inside
                    // Nearby's own Download folder, which scoped storage refuses
                    // to open from this app — and it refuses in a way that looks
                    // like a damaged image rather than a permission problem,
                    // because stat succeeds and only open fails.
                    val uri = pending.asFile()?.asUri()
                    if (uri == null) {
                        Log.w(TAG, "payload ${update.payloadId} completed with no uri")
                        emit(TransportEvent.PhotoFailed(update.payloadId))
                    } else {
                        emit(TransportEvent.PhotoArrived(update.payloadId, uri))
                    }
                }

                else -> {
                    arrivingFiles.remove(update.payloadId)
                    emit(TransportEvent.PhotoFailed(update.payloadId))
                }
            }
        }
    }

    override fun advertise(name: String) {
        client.startAdvertising(
            name,
            SERVICE_ID,
            lifecycle,
            AdvertisingOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build(),
        )
            .addOnSuccessListener { Log.i(TAG, "advertising as $name") }
            .addOnFailureListener { reportUnlessAlreadyBusy("advertising", it) }
    }

    override fun discover() {
        client.startDiscovery(
            SERVICE_ID,
            discovery,
            DiscoveryOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build(),
        )
            .addOnSuccessListener { Log.i(TAG, "discovering") }
            .addOnFailureListener { reportUnlessAlreadyBusy("looking for phones", it) }
    }

    /**
     * Surfaces a transport failure, except the two that mean "you are already
     * doing that".
     *
     * The reconnect loop calls advertise() or discover() on every attempt
     * without stopping the previous one, so Nearby answers
     * STATUS_ALREADY_ADVERTISING / STATUS_ALREADY_DISCOVERING. That is not a
     * failure — it is confirmation that the thing we want is running — but it
     * was being shown to the user as "Trouble looking for phones: 8002", which
     * reads as broken while the app is working correctly.
     */
    private fun reportUnlessAlreadyBusy(doing: String, cause: Throwable) {
        val code = (cause as? com.google.android.gms.common.api.ApiException)?.statusCode
        if (code == ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING ||
            code == ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING
        ) {
            Log.i(TAG, "already $doing — nothing to do")
            return
        }
        emit(TransportEvent.Failed(doing, cause))
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

    override fun startVideo(): OutputStream? {
        val to = connected ?: return null
        stopVideo()
        // Nearby reads the stream on its own thread as fast as it can drain it.
        // The buffer is deliberately generous: a stall here shows up as dropped
        // frames rather than as the encoder blocking the camera.
        val sink = PipedOutputStream()
        val source = PipedInputStream(sink, VIDEO_PIPE_BYTES)
        videoSink = sink
        client.sendPayload(to, Payload.fromStream(source))
            .addOnFailureListener { emit(TransportEvent.Failed("starting the video", it)) }
        return sink
    }

    override fun sendPhoto(file: java.io.File): Long? {
        val to = connected ?: return null
        val payload = runCatching { Payload.fromFile(file) }.getOrElse {
            emit(TransportEvent.Failed("sending the photo", it))
            return null
        }
        client.sendPayload(to, payload)
            .addOnFailureListener { emit(TransportEvent.Failed("sending the photo", it)) }
        return payload.id
    }

    override fun stopVideo() {
        runCatching { videoSink?.close() }
        videoSink = null
    }

    override fun stop() {
        stopVideo()
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        connected = null
        names.clear()
    }
}
