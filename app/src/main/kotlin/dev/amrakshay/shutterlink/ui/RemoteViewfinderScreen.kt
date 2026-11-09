package dev.amrakshay.shutterlink.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.amrakshay.shutterlink.camera.StreamHealth
import dev.amrakshay.shutterlink.camera.VideoDecoder
import dev.amrakshay.shutterlink.protocol.ENGINE_SHUTTERLINK
import dev.amrakshay.shutterlink.protocol.Message
import dev.amrakshay.shutterlink.transport.HidShutter
import dev.amrakshay.shutterlink.transport.Pairing
import dev.amrakshay.shutterlink.transport.PairingState
import dev.amrakshay.shutterlink.transport.ShutterKey
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import java.io.InputStream

private val TIMERS = listOf(0, 3, 10)

/**
 * The phone in your hand: the far camera's view, and the controls for it.
 *
 * Every control sends a message and then waits to be told what happened — none
 * of them change anything locally on the optimistic assumption it worked. A
 * viewfinder that lies about the zoom is worse than one that lags behind it.
 */
@Composable
fun RemoteViewfinderScreen(pairing: Pairing, state: PairingState, onBack: () -> Unit) {
    var decoder by remember { mutableStateOf<VideoDecoder?>(null) }
    var health by remember { mutableStateOf(StreamHealth()) }
    var zoom by remember { mutableStateOf(0f) }
    var front by remember { mutableStateOf(false) }
    var timer by remember { mutableStateOf(0) }
    var countdown by remember { mutableStateOf<Int?>(null) }
    var capturing by remember { mutableStateOf(false) }
    var surfaceReady by remember { mutableStateOf<android.view.Surface?>(null) }
    var engine by remember { mutableStateOf(ENGINE_SHUTTERLINK) }
    var pickingEngine by remember { mutableStateOf(false) }

    // Firing another camera app needs a keystroke, because its shutter button is
    // inside its own sandbox and nothing can reach it. This phone becomes a
    // Bluetooth keyboard to send one — the same trick a selfie remote uses.
    // Only set up when a third-party engine is actually chosen: registering as
    // an HID device is intrusive and most sessions never need it.
    val context = LocalContext.current
    val hid = remember { HidShutter(context) }
    var hidReady by remember { mutableStateOf(false) }
    var hidProblem by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(engine) {
        if (engine == ENGINE_SHUTTERLINK || hidReady) return@LaunchedEffect
        if (!hid.supported()) {
            hidProblem = "This phone cannot act as a Bluetooth keyboard"
            return@LaunchedEffect
        }
        hid.register { ok ->
            hidReady = ok
            if (!ok) hidProblem = "Could not register as a keyboard"
        }
        // The far phone has to be paired in Bluetooth settings, not merely
        // connected by ShutterLink — HID runs over classic Bluetooth.
        val target = hid.pairedDevices().firstOrNull {
            it.name != null && it.name == state.connected?.name
        }
        if (target == null) {
            hidProblem = "Pair the two phones in Bluetooth settings first"
        } else {
            hid.connectTo(target)
        }
    }

    DisposableEffect(Unit) { onDispose { hid.release() } }

    val stream: InputStream? = state.video

    // Decoding starts only when both halves exist: the Surface the SurfaceView
    // created, and the stream the camera opened. Either can arrive first.
    LaunchedEffect(stream, surfaceReady) {
        val surface = surfaceReady
        if (stream == null || surface == null) return@LaunchedEffect
        decoder?.stop()
        decoder = VideoDecoder(
            surface = surface,
            onHealth = { health = it },
            onEnded = { },
        ).also { it.start(stream) }
    }

    DisposableEffect(Unit) { onDispose { decoder?.stop() } }

    LaunchedEffect(Unit) {
        pairing.incoming.collect { message ->
            when (message) {
                is Message.Countdown -> countdown = message.secondsLeft
                Message.CaptureStarted -> {
                    countdown = null
                    capturing = true
                    if (engine != ENGINE_SHUTTERLINK) {
                        // The far phone has opened the other camera app. Give it
                        // a moment to actually have the camera — firing early
                        // does nothing at all — then press its shutter for it.
                        delay(2500)
                        if (!hid.press(ShutterKey.VOLUME_UP)) {
                            hidProblem = "Could not reach the other phone's shutter"
                            capturing = false
                        }
                    }
                }
                is Message.CaptureFailed -> capturing = false
                is Message.PhotoReady -> capturing = false
                else -> Unit
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {

        AndroidView(
            factory = { context ->
                SurfaceView(context).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            surfaceReady = holder.surface
                        }

                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) = Unit

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            surfaceReady = null
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (health.framesShown == 0L) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Waiting for the picture…", color = Ink.Muted, fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (stream == null) "The other phone has not started streaming"
                    else "Stream open, waiting for a keyframe",
                    color = Ink.Faint,
                    fontSize = 12.sp,
                )
            }
        }

        // Top strip: the facts, not decoration.
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Chip(state.connected?.name ?: "—", dot = true)
            state.latencyMillis?.let { Chip("$it ms") }
            Chip("${health.framesShown} f")
            if (health.framesDropped > 0) Chip("${health.framesDropped} dropped")
            Spacer(Modifier.weight(1f))
            Chip("Back", onClick = onBack)
        }

        countdown?.let {
            Text(
                it.toString(),
                color = Color.White,
                fontSize = 96.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Which app takes the shot. Only offered when the far phone has
            // more than ours, so it never appears as a menu of one.
            if (state.engines.size > 1) {
                val current = state.engines.firstOrNull { it.id == engine }
                Chip(
                    "Shooting with ${current?.label ?: "ShutterLink"}",
                    onClick = { pickingEngine = !pickingEngine },
                )
            }

            if (pickingEngine) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Ink.SurfaceDim, RoundedCornerShape(16.dp))
                        .border(1.dp, Ink.Line, RoundedCornerShape(16.dp))
                        .padding(8.dp),
                ) {
                    state.engines.forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { engine = option.id; pickingEngine = false }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        option.label,
                                        color = if (option.id == engine) Ink.Accent else Ink.Text,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    if (!option.tested) {
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "UNTESTED",
                                            color = Ink.Warning,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                                Text(
                                    if (option.id == ENGINE_SHUTTERLINK) {
                                        "Live view, zoom and timer keep working"
                                    } else {
                                        "Its own processing — live view pauses"
                                    },
                                    color = Ink.Faint,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }
            }

            state.theirCapabilities?.let { caps ->
                if (caps.maxZoom > caps.minZoom) {
                    Slider(
                        value = zoom,
                        onValueChange = {
                            zoom = it
                            pairing.send(Message.SetZoom(it))
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = Ink.Accent,
                            activeTrackColor = Ink.Accent,
                            inactiveTrackColor = Color(0x33FFFFFF),
                        ),
                        modifier = Modifier.fillMaxWidth(0.8f),
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(54.dp)
                        .background(Color(0xB80A0E12), RoundedCornerShape(14.dp))
                        .border(1.dp, Color(0x24FFFFFF), RoundedCornerShape(14.dp)),
                )

                Box(
                    Modifier
                        .size(78.dp)
                        .border(4.dp, Color(0x52FFFFFF), CircleShape)
                        .padding(6.dp)
                        .background(if (capturing) Color(0x80FFFFFF) else Color.White, CircleShape)
                        .clickable(enabled = !capturing) {
                            capturing = true
                            pairing.send(Message.Capture(timerSeconds = timer, engine = engine))
                        },
                )

                val canFlip = state.theirCapabilities
                    ?.let { it.hasFrontCamera && it.hasBackCamera } == true
                Box(
                    Modifier.size(54.dp)
                        .background(Color(0xB80A0E12), CircleShape)
                        .border(1.dp, Color(0x24FFFFFF), CircleShape)
                        .clickable(enabled = canFlip) {
                            front = !front
                            pairing.send(Message.SwitchCamera(front))
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("⇄", color = if (canFlip) Ink.Text else Ink.Faint, fontSize = 20.sp)
                }
            }

            Text(
                text = if (timer == 0) "Timer off" else "${timer}s timer",
                color = if (timer == 0) Ink.Muted else Ink.Accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable { timer = TIMERS[(TIMERS.indexOf(timer) + 1) % TIMERS.size] }
                    .padding(8.dp),
            )
        }

        hidProblem?.takeIf { engine != ENGINE_SHUTTERLINK }?.let {
            Text(
                it,
                color = Ink.Warning,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 96.dp),
            )
        }

        state.photoProgress?.let { percent ->
            Text(
                if (percent < 0) "Photo coming over…" else "Photo coming over… $percent%",
                color = Ink.Accent,
                fontSize = 13.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
            )
        }
    }
}

@Composable
private fun Chip(text: String, dot: Boolean = false, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .background(Color(0xB80A0E12), CircleShape)
            .border(1.dp, Color(0x1FFFFFFF), CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) {
            Box(Modifier.size(7.dp).background(Ink.Accent, CircleShape))
            Spacer(Modifier.width(7.dp))
        }
        Text(text, color = Ink.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}
