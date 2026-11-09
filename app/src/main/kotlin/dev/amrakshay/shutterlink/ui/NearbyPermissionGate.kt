package dev.amrakshay.shutterlink.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import dev.amrakshay.shutterlink.transport.NearbyPermissions

/**
 * Asks for what finding the other phone costs, with the reason beside each one.
 *
 * Asked here rather than at launch: a permission sheet that appears before the
 * user has done anything is the one they refuse, and on Android 10 this list
 * includes Location, which looks alarming in a camera app unless the screen
 * explains itself first.
 */
@Composable
fun NearbyPermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(NearbyPermissions.allGranted(context)) }
    var asked by remember { mutableStateOf(false) }
    var outstanding by remember { mutableStateOf(NearbyPermissions.missing(context)) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        asked = true
        outstanding = NearbyPermissions.missing(context)
        granted = outstanding.isEmpty()
    }

    if (granted) {
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
        Spacer(Modifier.height(24.dp))
        Text(
            "What this needs",
            color = Ink.Text,
            fontSize = 25.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Asked one at a time, with the reason. Nothing here is used for anything else.",
            color = Ink.Muted,
            fontSize = 14.sp,
            lineHeight = 21.sp,
        )
        Spacer(Modifier.height(22.dp))

        outstanding.forEach { permission ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
                    .background(Ink.Surface, RoundedCornerShape(15.dp))
                    .border(1.dp, Ink.Line, RoundedCornerShape(15.dp))
                    .padding(15.dp),
            ) {
                Column {
                    Text(
                        NearbyPermissions.label(permission),
                        color = Ink.Text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        NearbyPermissions.reason(permission),
                        color = Ink.Muted,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // Once Android stops showing the dialog, asking again silently does
        // nothing, so the button has to change rather than appear broken.
        val toSettings = asked
        Text(
            text = if (toSettings) "Open settings" else "Continue",
            color = Ink.AccentInk,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
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
                        launcher.launch(outstanding.toTypedArray())
                    }
                }
                .padding(vertical = 17.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Every permission is listed with its reason in SECURITY.md.",
            color = Ink.Faint,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.size(4.dp))
    }
}
