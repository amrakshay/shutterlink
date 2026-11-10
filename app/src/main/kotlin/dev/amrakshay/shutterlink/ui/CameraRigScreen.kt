package dev.amrakshay.shutterlink.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
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
import androidx.core.content.ContextCompat
import dev.amrakshay.shutterlink.camera.CameraSession
import dev.amrakshay.shutterlink.camera.CaptureEngines
import dev.amrakshay.shutterlink.camera.PhotoStore
import dev.amrakshay.shutterlink.camera.PreviewEncoder
import dev.amrakshay.shutterlink.camera.StreamQuality
import dev.amrakshay.shutterlink.camera.StreamingService
import dev.amrakshay.shutterlink.protocol.ENGINE_SHUTTERLINK
import dev.amrakshay.shutterlink.protocol.Message
import dev.amrakshay.shutterlink.transport.Pairing
import dev.amrakshay.shutterlink.transport.thisPhoneName
import kotlinx.coroutines.delay
import java.io.File

/**
 * The camera phone once it is connected: streaming, and doing as it is told.
 *
 * Shows almost nothing on purpose. This phone is propped up across the room,
 * and a bright screen full of controls would both light the subject and invite
 * someone to touch it.
 */
@Composable
fun CameraRigScreen(pairing: Pairing, linkIsFast: Boolean, onEnd: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val session = remember { CameraSession(context) }
    val photos = remember { PhotoStore(context) }

    var encoder by remember { mutableStateOf<PreviewEncoder?>(null) }
    var front by remember { mutableStateOf(false) }
    // Two things cap the quality, and they must not fight over one variable.
    //
    // The link: Nearby begins every connection on Bluetooth and upgrades to
    // Wi-Fi when it decides to — measured at 35 seconds on the test pair.
    // Sending 720p at 3 Mbps during that window pushes far more than Bluetooth
    // carries: the encoder's writes blocked for a full second at a time and the
    // picture fell behind and never caught up. So until the link is fast, LOW.
    //
    // The phone: thermal pressure lowers the ceiling and never raises it.
    //
    // Whichever is lower wins. Deriving it, rather than having both assign to
    // the same state, is what stops the link upgrade from quietly undoing a
    // thermal step-down.
    var thermalCap by remember { mutableStateOf(StreamQuality.HIGH) }
    val quality = if (linkIsFast) thermalCap else StreamQuality.LOW
    var streaming by remember { mutableStateOf(false) }
    var shots by remember { mutableIntStateOf(0) }
    var countdown by remember { mutableStateOf<Int?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var rotation by remember { mutableIntStateOf(0) }

    // Bumped on every resume to force a rebind.
    //
    // CameraX unbinds when the activity stops, which is what happens the moment
    // the screen locks. Nothing brought it back: the binding effect is keyed on
    // the camera and the quality, neither of which changed, so waking the phone
    // left a live connection with a dead camera — the control channel still
    // answering pings while the picture stayed frozen. That reads as a broken
    // app rather than a sleeping one.
    var bindEpoch by remember { mutableIntStateOf(0) }

    // Rebuilt on the screen coming back, not on ON_RESUME.
    //
    // Measured: with the app in the foreground and nothing else competing, the
    // stream stops when the screen goes off and the activity never leaves
    // RESUMED — so a lifecycle observer never fires. What actually happens is
    // that the system takes the camera away when the device locks, which CameraX
    // cannot undo by itself once the encoder's input surface has gone with it.
    //
    // ACTION_SCREEN_ON and ACTION_USER_PRESENT are the events that do arrive, so
    // the whole pipeline — encoder, binding, video channel — is rebuilt from
    // there.
    DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                bindEpoch++
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    var lastFacts by remember { mutableStateOf<dev.amrakshay.shutterlink.camera.CameraFacts?>(null) }

    // Start, and restart whenever the camera or the quality changes. Both need
    // the encoder torn down and rebuilt: the encoder's input Surface is fixed at
    // the size it was created with.
    LaunchedEffect(front, quality, bindEpoch) {
        runCatching {
            encoder?.stop()
            val sink = pairing.startVideo()
            if (sink == null) {
                problem = "No connection to stream to"
                return@runCatching
            }
            val fresh = PreviewEncoder(quality, sink) { problem = "The encoder stopped" }
            val surface = fresh.start()
            val facts = session.bindForStreaming(lifecycleOwner, surface, front) { degrees ->
                rotation = degrees
            }
            encoder = fresh
            lastFacts = facts
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
                    rotationDegrees = rotation,
                ),
            )
        }.onFailure {
            problem = it.message ?: "The camera would not start"
            streaming = false
        }
    }

    // Without this the camera stops within seconds of the screen going dark,
    // which is exactly the state this phone is meant to sit in.
    DisposableEffect(Unit) {
        StreamingService.start(context)
        onDispose {
            StreamingService.stop(context)
            encoder?.stop()
            session.release()
            pairing.stopVideo()
        }
    }

    // Step the quality down before the phone throttles itself. A stream that
    // quietly drops to 540p is far better than one that stutters because the
    // encoder is being thermally starved, and better than a phone that gets too
    // hot to hold halfway through a shoot.
    var thermal by remember { mutableStateOf("none") }
    DisposableEffect(Unit) {
        val power = context.getSystemService(PowerManager::class.java)
        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            thermal = when (status) {
                PowerManager.THERMAL_STATUS_NONE -> "none"
                PowerManager.THERMAL_STATUS_LIGHT -> "light"
                PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
                PowerManager.THERMAL_STATUS_SEVERE -> "severe"
                else -> "critical"
            }
            val wanted = when {
                status >= PowerManager.THERMAL_STATUS_SEVERE -> StreamQuality.LOW
                status >= PowerManager.THERMAL_STATUS_MODERATE -> StreamQuality.MEDIUM
                else -> null
            }
            // Only ever downwards. Climbing back the moment the phone cools
            // would oscillate, and each change costs a visible restart.
            if (wanted != null && wanted.height < thermalCap.height) thermalCap = wanted
        }
        power.addThermalStatusListener(ContextCompat.getMainExecutor(context), listener)
        onDispose { power.removeThermalStatusListener(listener) }
    }

    LaunchedEffect(Unit) {
        pairing.send(Message.Engines(CaptureEngines.discover(context)))
    }

    // Keyed on BOTH, and that matters.
    //
    // CameraX reports the angle asynchronously, usually after bindForStreaming
    // has already returned and sent Capabilities — so the first Capabilities
    // carries 0. Keyed on `rotation` alone, this resend fired while lastFacts
    // was still null, returned early, and never ran again: the controller was
    // left believing the picture was upright and displayed it on its side.
    //
    // It also covers the phone being turned over mid-session.
    LaunchedEffect(rotation, lastFacts) {
        val facts = lastFacts ?: return@LaunchedEffect
        pairing.send(
            Message.Capabilities(
                deviceName = thisPhoneName(),
                hasFrontCamera = facts.hasFrontCamera,
                hasBackCamera = facts.hasBackCamera,
                minZoom = facts.minZoom,
                maxZoom = facts.maxZoom,
                rotationDegrees = rotation,
            ),
        )
    }

    // Handing the shot to another camera app, and getting it back. The launcher
    // has to live out here: the result arrives on the activity, not inside the
    // coroutine that asked for it.
    var handedOff by remember { mutableStateOf<Pair<File, Long>?>(null) }
    val externalCapture = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { _ ->
        val (file, startedAt) = handedOff ?: return@rememberLauncherForActivityResult
        handedOff = null

        // §11.1: look before adding. Some camera apps save their own gallery
        // copy when launched for a result and some do not, so adding one
        // unconditionally leaves half of them with duplicates.
        val theirs = photos.newestSince(startedAt)
        val toSend: File? = when {
            theirs != null -> photos.copyOut(theirs.first, theirs.second)
            file.length() > 0 -> { photos.publish(file); file }
            else -> null
        }

        if (toSend == null) {
            pairing.send(Message.CaptureFailed("that app did not return a photo"))
        } else {
            shots += 1
            val id = pairing.startPhotoTransfer(toSend)
            if (id != null) {
                pairing.send(
                    Message.PhotoReady(
                        payloadId = id,
                        name = toSend.name,
                        bytes = toSend.length(),
                        width = 0,
                        height = 0,
                    ),
                )
            }
        }
    }

    // Do as the controller says.
    LaunchedEffect(Unit) {
        pairing.incoming.collect { message ->
            when (message) {
                is Message.SetZoom -> session.setLinearZoom(message.linear)

                is Message.SwitchCamera -> front = message.front

                // The controller asking for a specific quality sets the
                // ceiling; the link and thermal caps still apply on top, so it
                // cannot talk us into pushing HD down a Bluetooth link.
                is Message.StartPreview -> thermalCap =
                    StreamQuality(message.width, message.height, message.fps, message.bitrate)

                Message.StopPreview -> {
                    encoder?.stop(); encoder = null; streaming = false
                }

                is Message.Capture -> if (message.engine != ENGINE_SHUTTERLINK) {
                    // Another app takes the photo. The live view stops while it
                    // holds the camera — unavoidable, and said plainly on the
                    // controller rather than hidden behind a frozen frame.
                    pairing.send(Message.CaptureStarted)
                    encoder?.stop()
                    encoder = null
                    streaming = false

                    val file = File(
                        File(context.cacheDir, "captures").apply { mkdirs() },
                        photos.newName(),
                    )
                    val uri: Uri = FileProvider.getUriForFile(
                        context,
                        context.packageName + ".captures",
                        file,
                    )
                    handedOff = file to System.currentTimeMillis()
                    val intent = android.content.Intent(
                        android.provider.MediaStore.ACTION_IMAGE_CAPTURE,
                    ).apply {
                        setPackage(message.engine)
                        putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
                        addFlags(android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    }
                    runCatching { externalCapture.launch(intent) }.onFailure {
                        handedOff = null
                        pairing.send(Message.CaptureFailed("could not open that app"))
                    }
                } else {
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
                    thermal = thermal,
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
