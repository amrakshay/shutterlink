package dev.amrakshay.shutterlink.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.net.Uri
import android.view.OrientationEventListener
import android.view.Surface
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
import dev.amrakshay.shutterlink.camera.CameraHoldService
import dev.amrakshay.shutterlink.protocol.ENGINE_SHUTTERLINK
import dev.amrakshay.shutterlink.protocol.Message
import dev.amrakshay.shutterlink.transport.Pairing
import dev.amrakshay.shutterlink.transport.thisPhoneName
import kotlinx.coroutines.delay
import java.io.File

/**
 * The camera phone once it is connected: holding the camera, and doing as it is
 * told.
 *
 * Shows almost nothing on purpose. This phone is propped up across the room,
 * and a bright screen full of controls would both light the subject and invite
 * someone to touch it.
 *
 * It sends no live picture. The camera is bound for stills only and sits idle
 * between shots, which is the whole point of the capture-and-review design: a
 * phone that is not encoding video at thirty frames a second stays cool, keeps
 * its battery, and has nothing to fall behind on.
 */
@Composable
fun CameraRigScreen(pairing: Pairing, onEnd: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val session = remember { CameraSession(context) }
    val photos = remember { PhotoStore(context) }

    var front by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var shots by remember { mutableIntStateOf(0) }
    var countdown by remember { mutableStateOf<Int?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var thermal by remember { mutableStateOf("none") }

    // Bumped on every screen-on to force a rebind.
    //
    // The system takes the camera away when the device locks, and CameraX does
    // not take it back by itself. Nothing noticed: the binding effect is keyed
    // on the camera selector, which has not changed, so waking the phone left a
    // live connection with a dead camera — the control channel still answering
    // pings while the shutter did nothing.
    //
    // Measured: with the app in the foreground the activity never leaves
    // RESUMED when the screen goes off, so a lifecycle observer never fires.
    // ACTION_SCREEN_ON and ACTION_USER_PRESENT are the events that do arrive.
    var bindEpoch by remember { mutableIntStateOf(0) }
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

    // Bind for stills, and rebind on a flip or a wake.
    LaunchedEffect(front, bindEpoch) {
        runCatching {
            val facts = session.bindForCapture(lifecycleOwner, front)
            ready = true
            problem = null
            pairing.publishCapabilities(
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
            ready = false
        }
    }

    // The activity is locked to portrait, so the display rotation never changes
    // — which means CameraX would tag every capture as portrait however the
    // phone is actually being held. A camera that gets propped up WILL be
    // turned on its side, so the physical orientation has to come from the
    // sensor instead.
    //
    // This matters more here than anywhere else in the app: with no live view,
    // the photo that comes back is the only thing anyone sees. A sideways one
    // is not a cosmetic problem, it is the entire output being wrong.
    DisposableEffect(Unit) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                session.imageCapture?.targetRotation = when (degrees) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }

    // Without this the camera is taken away within seconds of the screen going
    // dark, which is exactly the state this phone is meant to sit in.
    DisposableEffect(Unit) {
        CameraHoldService.start(context)
        onDispose {
            CameraHoldService.stop(context)
            session.release()
        }
    }

    // Reported to the controller rather than acted on. Nothing is being encoded
    // any more, so there is no quality to step down — but a phone that is
    // cooking is still worth saying out loud before a long shoot.
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
        }
        power.addThermalStatusListener(ContextCompat.getMainExecutor(context), listener)
        onDispose { power.removeThermalStatusListener(listener) }
    }

    LaunchedEffect(Unit) {
        pairing.send(Message.Engines(CaptureEngines.discover(context)))
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

                is Message.Capture -> if (message.engine != ENGINE_SHUTTERLINK) {
                    pairing.send(Message.CaptureStarted)

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
                    ready = ready,
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
                .background(if (ready) Ink.AccentWell else Ink.SurfaceDim, CircleShape)
                .border(1.dp, if (ready) Ink.Positive else Ink.Line, CircleShape)
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(8.dp)
                    .background(if (ready) Ink.Positive else Ink.Faint, CircleShape),
            )
            Spacer(Modifier.width(9.dp))
            Text(
                if (ready) "Ready to shoot" else "Camera not ready",
                color = if (ready) Ink.Positive else Ink.Faint,
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
            Stat("$shots", "Shots", Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            Stat(if (front) "Front" else "Back", "Camera", Modifier.weight(1f))
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
