package dev.amrakshay.shutterlink.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Stops the screen turning off while the shutter is open.
 *
 * This phone is in your hand, but you are usually looking at the other one or
 * standing in the shot, so the inactivity timeout arrives at exactly the wrong
 * moment. A remote whose button has gone to sleep is a broken remote.
 *
 * It is load-bearing rather than a convenience: the HID connection is held by
 * the activity, so the screen locking tears it down and the next press goes
 * nowhere.
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
