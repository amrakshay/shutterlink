package dev.amrakshay.shutterlink.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Stops the screen turning off for as long as the app is open.
 *
 * Applied once at the root rather than per screen. Every screen in this app is
 * either pointing a camera at something or watching one, and in both cases
 * nobody is holding the phone — that is the whole point of it. A phone propped
 * up across the room will always hit its inactivity timeout.
 *
 * It is also load-bearing, not a convenience. CameraX is bound to the activity
 * lifecycle, so the screen locking stops the activity, unbinds the camera and
 * kills the stream; the foreground service keeps the process alive but not the
 * camera. Dimming the screen so it does not light the subject (Phase 6) is a
 * different thing from letting it lock, and only the first is wanted.
 *
 * The flag is cleared on the way out, so it never outlives the app.
 */
@Composable
fun KeepScreenOn() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
