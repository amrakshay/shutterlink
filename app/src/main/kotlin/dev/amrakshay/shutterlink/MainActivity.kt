package dev.amrakshay.shutterlink

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.amrakshay.shutterlink.input.KeyVerdict
import dev.amrakshay.shutterlink.input.VolumeShutter
import dev.amrakshay.shutterlink.ui.Ink
import dev.amrakshay.shutterlink.ui.RemoteScreen
import dev.amrakshay.shutterlink.ui.ShutterLinkTheme

class MainActivity : ComponentActivity() {

    /**
     * Set by the shutter screen while it is able to take a photo, and cleared
     * when it is not.
     *
     * Null is meaningful: it is how the volume keys go back to being volume
     * keys the moment the app cannot shoot.
     */
    private var fireShutter: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ShutterLinkTheme { App(onShutterChanged = { fireShutter = it }) }
        }
    }

    /**
     * Lets the phone's own volume keys take the photo.
     *
     * The point of the app is that you are standing in the shot several metres
     * away, often with the phone at your side. A button you can find by feel
     * beats a circle you have to look at — which is why every physical remote
     * works this way.
     *
     * `dispatchKeyEvent` rather than `onKeyDown`, because the release has to be
     * swallowed too: hand that back to the system and the volume slider still
     * appears over whatever you are doing.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val verdict = VolumeShutter.decide(
            isVolumeKey = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN,
            isDown = event.action == KeyEvent.ACTION_DOWN,
            isFirstPress = event.repeatCount == 0,
            canFire = fireShutter != null,
        )
        if (verdict != KeyVerdict.IGNORE || event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        ) {
            // Logged because "the volume key did nothing" and "the volume key
            // changed the volume instead of taking a photo" are the two
            // reports this feature will generate, and they are
            // indistinguishable without knowing which verdict was reached.
            android.util.Log.i(
                "ShutterLink/Keys",
                "volume key action=${event.action} repeat=${event.repeatCount} " +
                    "canFire=${fireShutter != null} -> $verdict",
            )
        }
        return when (verdict) {
            KeyVerdict.FIRE -> {
                fireShutter?.invoke()
                true
            }

            KeyVerdict.SWALLOW -> true
            KeyVerdict.IGNORE -> super.dispatchKeyEvent(event)
        }
    }
}

/**
 * Everything the app needs permission for, which is now only Bluetooth.
 *
 * Worth saying plainly because earlier versions of this app asked for a great
 * deal more: there is no location permission, no storage permission, no camera
 * permission and no internet permission. Those were all consequences of trying
 * to stream a viewfinder and send photos back over a local network. A keyboard
 * needs none of it.
 */
private fun bluetoothPermissions(): List<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        )
    } else {
        // Pre-12 these are install-time permissions, so nothing to ask for.
        emptyList()
    }

@Composable
private fun App(onShutterChanged: ((() -> Unit)?) -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            bluetoothPermissions().all {
                ContextCompat.checkSelfPermission(context, it) ==
                    PackageManager.PERMISSION_GRANTED
            },
        )
    }

    val ask = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result -> granted = result.values.all { it } }

    if (granted) {
        RemoteScreen(onShutterChanged = onShutterChanged)
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink.Ground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement_Center,
    ) {
        Text(
            "ShutterLink needs Bluetooth",
            color = Ink.Text,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "To act as a Bluetooth keyboard for your other phone's camera. " +
                "That is the only thing this app asks for — no location, no " +
                "photos, no internet.",
            color = Ink.Muted,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            "Allow Bluetooth",
            color = Ink.AccentInk,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .background(Ink.Accent, RoundedCornerShape(15.dp))
                .clickable { ask.launch(bluetoothPermissions().toTypedArray()) }
                .padding(vertical = 15.dp),
        )
    }
}

private val Arrangement_Center = androidx.compose.foundation.layout.Arrangement.Center
