package dev.amrakshay.shutterlink.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor

/**
 * Turns [Message]s into bytes and back.
 *
 * CBOR rather than JSON: these go over a link that also carries video, and the
 * control channel should stay out of its way.
 *
 * The two phones are separate installs and will routinely run different
 * versions — one updated from a release, one still on a sideloaded build. So
 * [decode] returns null for anything it does not understand instead of throwing.
 * A newer peer sending a message this build has never heard of must be ignored,
 * not treated as a broken connection.
 */
@OptIn(ExperimentalSerializationApi::class)
object MessageCodec {

    private val cbor = Cbor {
        ignoreUnknownKeys = true
    }

    fun encode(message: Message): ByteArray =
        cbor.encodeToByteArray(Message.serializer(), message)

    /** Null means "not something this build understands" — drop it and carry on. */
    fun decode(bytes: ByteArray): Message? = try {
        cbor.decodeFromByteArray(Message.serializer(), bytes)
    } catch (_: Exception) {
        // Deliberately broad. Garbage on the wire, a truncated payload and a
        // message type from a newer build all mean the same thing here: there is
        // nothing useful to act on, and dropping the connection over it would be
        // worse than ignoring it.
        null
    }
}
