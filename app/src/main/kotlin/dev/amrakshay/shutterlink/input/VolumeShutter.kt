package dev.amrakshay.shutterlink.input

/** What the activity should do with a key event. */
enum class KeyVerdict {
    /** Take the photo, and swallow the event. */
    FIRE,

    /** Swallow it without firing, so the volume does not move. */
    SWALLOW,

    /** Not ours. Let the system have it. */
    IGNORE,
}

/**
 * Whether a volume key press should act as the shutter.
 *
 * Pure, and separated from the activity on purpose. The rules are small but
 * every one of them is a way to get this subtly wrong, and none of them is
 * reachable from a test once they are tangled up with `KeyEvent`:
 *
 * - **Both down and up must be swallowed.** Consuming only the press leaves
 *   the release to the system, which still shows the volume slider.
 * - **Only the first press fires.** Holding the key repeats the event, and a
 *   held button would otherwise empty the shutter into a burst nobody asked
 *   for.
 * - **Nothing is swallowed when the app cannot shoot.** If there is no phone
 *   connected, the volume keys must do what they always do. Stealing them from
 *   someone who is just trying to turn the music down, in an app that is doing
 *   nothing, would be rude.
 */
object VolumeShutter {

    fun decide(
        isVolumeKey: Boolean,
        isDown: Boolean,
        isFirstPress: Boolean,
        canFire: Boolean,
    ): KeyVerdict = when {
        !isVolumeKey -> KeyVerdict.IGNORE
        !canFire -> KeyVerdict.IGNORE
        isDown && isFirstPress -> KeyVerdict.FIRE
        else -> KeyVerdict.SWALLOW
    }
}
