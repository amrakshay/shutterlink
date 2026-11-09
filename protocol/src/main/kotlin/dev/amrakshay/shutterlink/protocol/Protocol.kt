package dev.amrakshay.shutterlink.protocol

/**
 * Everything the controller phone and the camera phone say to each other.
 *
 * The two phones are separate installs and will routinely run different versions of
 * the app — one updated from a release, one still on a sideloaded build. So the
 * version is negotiated in the first exchange rather than assumed, and an unknown
 * message is ignored rather than fatal.
 */
const val PROTOCOL_VERSION: Int = 1

/** The lowest peer version this build can still talk to. */
const val MIN_SUPPORTED_PROTOCOL_VERSION: Int = 1

/** Which side of the connection a device is playing. */
enum class Role {
    /** Propped up far away, holding the camera. */
    CAMERA,

    /** In your hand: shows the preview, sends the commands. */
    CONTROLLER,
}

/**
 * True when a peer announcing [peerVersion] can be talked to.
 *
 * Deliberately permissive upwards: a newer peer is assumed to still understand this
 * version's messages, because the alternative is refusing to connect to a phone that
 * would have worked. Downwards it is strict, because an older peer genuinely lacks
 * message types we would send.
 */
fun isCompatible(peerVersion: Int): Boolean = peerVersion >= MIN_SUPPORTED_PROTOCOL_VERSION
