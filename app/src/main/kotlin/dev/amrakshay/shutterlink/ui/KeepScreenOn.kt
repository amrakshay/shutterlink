package dev.amrakshay.shutterlink.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Stops the screen turning off while this screen is showing.
 *
 * Both phones need it, for different reasons.
 *
 * On the controller it is the obvious one: a viewfinder that blanks while you
 * are standing still composing a shot is useless, and nothing is touching the
 * screen by design — you are in the photo.
 *
 * On the camera phone it is load-bearing. CameraX is bound to the activity's
 * lifecycle, so the screen locking stops the activity, which unbinds the camera
 * and kills the stream — the foreground service keeps the process alive but not
 * the camera. Dimming the screen (Phase 6) is a different thing from letting it
 * lock, and only the first is wanted.
 *
 * The flag is cleared on the way out, so it never outlives the screen that
 * asked for it.
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
