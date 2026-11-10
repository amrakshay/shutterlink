package dev.amrakshay.shutterlink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amrakshay.shutterlink.protocol.ENGINE_SHUTTERLINK
import dev.amrakshay.shutterlink.protocol.Message
import dev.amrakshay.shutterlink.transport.HidShutter
import dev.amrakshay.shutterlink.transport.Pairing
import dev.amrakshay.shutterlink.transport.PairingState
import dev.amrakshay.shutterlink.transport.ShutterKey
import kotlinx.coroutines.delay

private val TIMERS = listOf(0, 3, 10)

/**
 * The phone in your hand: the shutter for the camera across the room.
 *
 * There is deliberately no live picture here. Sending one meant encoding video
 * on the far phone and decoding it on this one, and on a congested network the
 * result was a viewfinder a second or more behind reality — which is worse than
 * no viewfinder at all, because you frame against something that has already
 * happened and believe you are looking at now.
 *
 * So the loop is shoot, look, shoot again. The photo comes back over the same
 * link at full quality a moment after the shutter, and deciding whether to
 * retake is the framing step. It is slower than a live view that works, and far
 * better than a live view that lies.
 *
 * Every control sends a message and then waits to be told what happened — none
 * of them change anything locally on the optimistic assumption it worked.
 */
@Composable
fun RemoteCaptureScreen(pairing: Pairing, state: PairingState, onBack: () -> Unit) {
    var zoom by remember { mutableStateOf(0f) }
    var front by remember { mutableStateOf(false) }
    var timer by remember { mutableStateOf(0) }
    var countdown by remember { mutableStateOf<Int?>(null) }
    var capturing by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var theirStatus by remember { mutableStateOf<Message.Status?>(null) }
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

    LaunchedEffect(Unit) {
        pairing.incoming.collect { message ->
            when (message) {
                is Message.Countdown -> countdown = message.secondsLeft
                is Message.Status -> theirStatus = message
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
                is Message.CaptureFailed -> {
                    capturing = false
                    failure = message.reason
                }
                is Message.PhotoReady -> {
                    capturing = false
                    failure = null
                }
                else -> Unit
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Ink.Ground)) {

        // Top strip. The facts scroll rather than wrap: on a 720px-wide phone
        // the row overflowed and Android stacked the last chip one letter per
        // line, which looked like a rendering fault.
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Chip("Back", onClick = onBack)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Chip(state.connected?.name ?: "—", dot = true)
                state.latencyMillis?.let { Chip("$it ms") }
                theirStatus?.let { Chip("${it.batteryPercent}%") }
                theirStatus?.takeIf { it.shotsTaken > 0 }?.let { Chip("${it.shotsTaken} shots") }
                theirStatus?.takeIf { it.thermal != "none" && it.thermal != "light" }?.let {
                    Chip("running ${it.thermal}")
                }
            }
        }

        // The middle of the screen. There is no picture to put here, so it says
        // what the far phone is doing instead — which during a capture is the
        // only thing the user wants to know.
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val progress = state.photoProgress
            when {
                countdown != null -> {
                    Text(
                        countdown.toString(),
                        color = Ink.Text,
                        fontSize = 96.sp,
                        fontWeight = FontWeight.Light,
                    )
                }

                progress != null -> {
                    Text(
                        "Bringing the photo over",
                        color = Ink.Text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(14.dp))
                    if (progress < 0) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = Ink.Accent,
                            trackColor = Color(0x33FFFFFF),
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = Ink.Accent,
                            trackColor = Color(0x33FFFFFF),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        // Said plainly, because the difference is large enough
                        // to look like a hang: a 3 MB photo is about a second
                        // over Wi-Fi and the better part of a minute over
                        // Bluetooth.
                        if (state.linkIsFast) {
                            if (progress < 0) "Starting the transfer" else "$progress%"
                        } else {
                            "Still on Bluetooth, so this takes a while"
                        },
                        color = Ink.Faint,
                        fontSize = 12.sp,
                    )
                }

                capturing -> {
                    Text(
                        "Taking the photo",
                        color = Ink.Text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = Ink.Accent,
                        trackColor = Color(0x33FFFFFF),
                    )
                }

                else -> {
                    Text(
                        "Ready",
                        color = Ink.Text,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Press the shutter and the photo comes back here to " +
                            "look at. If the framing is off, move the other " +
                            "phone and shoot again.",
                        color = Ink.Muted,
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            failure?.let {
                Spacer(Modifier.height(16.dp))
                Text(
                    it,
                    color = Ink.Warning,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
            }

            hidProblem?.takeIf { engine != ENGINE_SHUTTERLINK }?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = Ink.Warning, fontSize = 12.sp, textAlign = TextAlign.Center)
            }
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
                                        "Zoom and the timer work"
                                    } else {
                                        "Its own processing — zoom and timer do not apply"
                                    },
                                    color = Ink.Faint,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }
            }

            // Zoom is a blind control now: nothing shows what it did until the
            // photo comes back. Still worth having — it changes the shot, and
            // walking the far phone closer is not always possible — but it is
            // only offered for our own camera, because another app will not
            // honour it.
            state.theirCapabilities?.let { caps ->
                if (caps.maxZoom > caps.minZoom && engine == ENGINE_SHUTTERLINK) {
                    Text(
                        if (zoom <= 0f) "Zoom" else "Zoom ${"%.1f".format(
                            caps.minZoom + zoom * (caps.maxZoom - caps.minZoom),
                        )}×",
                        color = if (zoom <= 0f) Ink.Muted else Ink.Accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
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
                        .background(Ink.SurfaceDim, RoundedCornerShape(14.dp))
                        .border(1.dp, Ink.Line, RoundedCornerShape(14.dp)),
                )

                val busy = capturing || countdown != null || state.photoProgress != null
                Box(
                    Modifier
                        .size(78.dp)
                        .border(4.dp, Color(0x52FFFFFF), CircleShape)
                        .padding(6.dp)
                        .background(if (busy) Color(0x80FFFFFF) else Color.White, CircleShape)
                        .clickable(enabled = !busy) {
                            capturing = true
                            failure = null
                            pairing.send(Message.Capture(timerSeconds = timer, engine = engine))
                        },
                )

                val canFlip = state.theirCapabilities
                    ?.let { it.hasFrontCamera && it.hasBackCamera } == true
                Box(
                    Modifier.size(54.dp)
                        .background(Ink.SurfaceDim, CircleShape)
                        .border(1.dp, Ink.Line, CircleShape)
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
    }
}

@Composable
private fun Chip(text: String, dot: Boolean = false, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .background(Ink.SurfaceDim, CircleShape)
            .border(1.dp, Ink.Line, CircleShape)
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
