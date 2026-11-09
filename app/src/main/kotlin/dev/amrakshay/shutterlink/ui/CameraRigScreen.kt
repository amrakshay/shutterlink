package dev.amrakshay.shutterlink.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amrakshay.shutterlink.camera.CameraSession
import dev.amrakshay.shutterlink.camera.PhotoStore
import dev.amrakshay.shutterlink.camera.PreviewEncoder
import dev.amrakshay.shutterlink.camera.StreamQuality
import dev.amrakshay.shutterlink.protocol.Message
import dev.amrakshay.shutterlink.transport.Pairing
import dev.amrakshay.shutterlink.transport.thisPhoneName
import kotlinx.coroutines.delay

/**
 * The camera phone once it is connected: streaming, and doing as it is told.
 *
 * Shows almost nothing on purpose. This phone is propped up across the room,
 * and a bright screen full of controls would both light the subject and invite
 * someone to touch it.
 */
@Composable
fun CameraRigScreen(pairing: Pairing, onEnd: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val session = remember { CameraSession(context) }
    val photos = remember { PhotoStore(context) }

    var encoder by remember { mutableStateOf<PreviewEncoder?>(null) }
    var front by remember { mutableStateOf(false) }
    var quality by remember { mutableStateOf(StreamQuality.HIGH) }
    var streaming by remember { mutableStateOf(false) }
    var shots by remember { mutableIntStateOf(0) }
    var countdown by remember { mutableStateOf<Int?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }

    // Start, and restart whenever the camera or the quality changes. Both need
    // the encoder torn down and rebuilt: the encoder's input Surface is fixed at
    // the size it was created with.
    LaunchedEffect(front, quality) {
        runCatching {
            encoder?.stop()
            val sink = pairing.startVideo()
            if (sink == null) {
                problem = "No connection to stream to"
                return@runCatching
            }
            val fresh = PreviewEncoder(quality, sink) { problem = "The encoder stopped" }
            val surface = fresh.start()
            val facts = session.bindForStreaming(lifecycleOwner, surface, front)
            encoder = fresh
            streaming = true

            // Now that the camera is bound we know what it can really do, so the
            // controller can draw controls that match rather than guesses.
            pairing.send(
                Message.Capabilities(
                    deviceName = thisPhoneName(),
                    hasFrontCamera = facts.hasFrontCamera,
                    hasBackCamera = facts.hasBackCamera,
                    minZoom = facts.minZoom,
                    maxZoom = facts.maxZoom,
                ),
            )
        }.onFailure {
            problem = it.message ?: "The camera would not start"
            streaming = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            encoder?.stop()
            session.release()
            pairing.stopVideo()
        }
    }

    // Do as the controller says.
    LaunchedEffect(Unit) {
        pairing.incoming.collect { message ->
            when (message) {
                is Message.SetZoom -> session.setLinearZoom(message.linear)

                is Message.SwitchCamera -> front = message.front

                is Message.StartPreview -> quality =
                    StreamQuality(message.width, message.height, message.fps, message.bitrate)

                Message.StopPreview -> {
                    encoder?.stop(); encoder = null; streaming = false
                }

                is Message.Capture -> {
                    val capture = session.imageCapture
                    if (capture == null) {
                        pairing.send(Message.CaptureFailed("the camera is not ready"))
                    } else {
                        for (left in message.timerSeconds downTo 1) {
                            countdown = left
                            pairing.send(Message.Countdown(left))
                            delay(1000)
                        }
                        countdown = null
                        pairing.send(Message.CaptureStarted)
                        runCatching {
                            val file = photos.captureToFile(capture, mirrored = front)
                            // Into this phone's gallery first: it is the original
                            // and the safe copy, whatever happens to the transfer.
                            photos.publish(file)
                            shots += 1
                            val id = pairing.startPhotoTransfer(file)
                            if (id != null) {
                                pairing.send(
                                    Message.PhotoReady(
                                        payloadId = id,
                                        name = file.name,
                                        bytes = file.length(),
                                        width = 0,
                                        height = 0,
                                    ),
                                )
                            }
                        }.onFailure {
                            pairing.send(Message.CaptureFailed(it.message ?: "capture failed"))
                        }
                    }
                }

                else -> Unit
            }
        }
    }

    // Tell the controller how this phone is doing, so it can warn before the
    // battery dies or the phone throttles mid-shoot.
    LaunchedEffect(Unit) {
        while (true) {
            pairing.send(
                Message.Status(
                    batteryPercent = batteryPercent(context),
                    thermal = "unknown",
                    streaming = streaming,
                    shotsTaken = shots,
                ),
            )
            delay(10_000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink.Ground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier
                .background(if (streaming) Ink.AccentWell else Ink.SurfaceDim, CircleShape)
                .border(1.dp, if (streaming) Ink.Positive else Ink.Line, CircleShape)
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(8.dp)
                    .background(if (streaming) Ink.Positive else Ink.Faint, CircleShape),
            )
            Spacer(Modifier.width(9.dp))
            Text(
                if (streaming) "Live" else "Not streaming",
                color = if (streaming) Ink.Positive else Ink.Faint,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Spacer(Modifier.height(50.dp))
        countdown?.let {
            Text(it.toString(), color = Ink.Text, fontSize = 72.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.height(20.dp))
        }

        Text(
            "Controlled from your other phone",
            color = Ink.Text,
            fontSize = 23.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "You can put this phone down. Leave it pointing where you want the photo taken.",
            color = Ink.Muted,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth()) {
            Stat("${quality.height}p", "Sending", Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            Stat("$shots", "Shots", Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            Stat("${batteryPercent(context)}%", "Battery", Modifier.weight(1f))
        }

        problem?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = Ink.Warning, fontSize = 13.sp, textAlign = TextAlign.Center)
        }

        Spacer(Modifier.weight(1f))
        Text(
            "End session",
            color = Ink.Destructive,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Ink.Line, RoundedCornerShape(15.dp))
                .clickable(onClick = onEnd)
                .padding(vertical = 16.dp),
        )
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Ink.SurfaceDim, RoundedCornerShape(15.dp))
            .border(1.dp, Ink.Line, RoundedCornerShape(15.dp))
            .padding(vertical = 15.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, color = Ink.Text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(3.dp))
        Text(label, color = Ink.Faint, fontSize = 11.sp)
    }
}

private fun batteryPercent(context: Context): Int {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    return if (level >= 0 && scale > 0) level * 100 / scale else -1
}
