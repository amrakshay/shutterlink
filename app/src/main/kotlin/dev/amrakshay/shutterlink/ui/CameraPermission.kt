package dev.amrakshay.shutterlink.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

/**
 * Asks for the camera, and explains itself when refused.
 *
 * Only the camera here: the Bluetooth, Wi-Fi and location permissions arrive
 * with the transport in Phase 2, and asking for them before anything uses them
 * is how an app earns a refusal it never recovers from.
 */
@Composable
fun CameraPermission(content: @Composable () -> Unit) {
    val context = LocalContext.current

    fun granted() = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED

    var allowed by remember { mutableStateOf(granted()) }
    var asked by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { result ->
        allowed = result
        asked = true
    }

    LaunchedEffect(Unit) {
        if (!allowed) launcher.launch(Manifest.permission.CAMERA)
    }

    if (allowed) {
        content()
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink.Ground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
    ) {
        Spacer(Modifier.weight(1f))
        Text(
            "ShutterLink needs the camera",
            color = Ink.Text,
            fontSize = 25.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 30.sp,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "It is a camera app, so there is nothing it can do without it. Nothing is recorded " +
                "or sent anywhere.",
            color = Ink.Muted,
            fontSize = 15.sp,
            lineHeight = 22.sp,
        )
        Spacer(Modifier.weight(1f))

        // Once Android stops showing the dialog, asking again does nothing — so
        // after a refusal the button has to go to Settings instead, or it
        // becomes a button that visibly does nothing.
        val toSettings = asked
        Text(
            text = if (toSettings) "Open settings" else "Allow the camera",
            color = Ink.AccentInk,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .fillMaxWidth()
                .background(Ink.Accent, RoundedCornerShape(16.dp))
                .clickable {
                    if (toSettings) {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ),
                        )
                    } else {
                        launcher.launch(Manifest.permission.CAMERA)
                    }
                }
                .padding(vertical = 17.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Every permission is listed with its reason in SECURITY.md.",
            color = Ink.Faint,
            fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
}
