package dev.amrakshay.shutterlink.ui

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
    var videoSize by remember { mutableStateOf(1280 to 720) }
    // Held so the transform can be re-applied whenever the angle or the picture
    // size changes, not only when the view happens to be recreated.
    var textureView by remember { mutableStateOf<TextureView?>(null) }
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
            onSize = { w, h -> videoSize = w to h },
            clockOffsetMillis = { pairing.clockOffsetMillis },
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

        // The camera sensor is landscape and its buffers reach the encoder
        // untransformed, so a phone propped up in portrait sends frames lying on
        // their side. The far phone tells us the angle (only it can know it —
        // it depends on the sensor mounting and how the phone is being held) and
        // the picture is turned here.
        //
        // A TextureView rather than a SurfaceView because only a TextureView can
        // be transformed; and it is sized to the rotated picture's aspect ratio
        // so the rotation needs no distorting scale to fit.
        val rotation = state.theirCapabilities?.rotationDegrees ?: 0
        val rotationRef = rememberUpdatedState(rotation)
        val (vw, vh) = videoSize
        val shownAspect =
            if (rotation % 180 == 90) vh.toFloat() / vw else vw.toFloat() / vh

        // Re-apply whenever the angle, the picture size or the view changes.
        // Doing it only inside AndroidView's update was not enough: update can
        // run before the view has been laid out, and a transform computed from
        // a zero width is dropped.
        LaunchedEffect(textureView, rotation, videoSize) {
            val view = textureView ?: return@LaunchedEffect
            var tries = 0
            while (view.width == 0 && tries < 20) {
                kotlinx.coroutines.delay(50)
                tries++
            }
            applyRotation(view, rotation, view.width, view.height)
        }

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { context ->
                    TextureView(context).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(
                                texture: SurfaceTexture,
                                width: Int,
                                height: Int,
                            ) {
                                textureView = this@apply
                                surfaceReady = android.view.Surface(texture)
                            }

                            override fun onSurfaceTextureSizeChanged(
                                texture: SurfaceTexture,
                                width: Int,
                                height: Int,
                            ) = applyRotation(this@apply, rotationRef.value, width, height)

                            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                                surfaceReady = null
                                return true
                            }

                            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                        }
                    }
                },
                update = { view -> textureView = view },
                modifier = Modifier.fillMaxWidth().aspectRatio(shownAspect),
            )
        }

        // Say what is happening instead of showing a picture that is a second
        // behind. Nearby starts every connection on Bluetooth and moves it to
        // Wi-Fi when it is ready; until then the stream is deliberately small,
        // and pretending otherwise just makes the app feel broken.
        if (health.framesShown == 0L) {
            Column(
                Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Starting the camera…",
                    color = Ink.Text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Ink.Accent,
                    trackColor = Color(0x33FFFFFF),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    if (stream == null) "Waiting for the other phone to start sending"
                    else "Stream open — waiting for the first picture",
                    color = Ink.Faint,
                    fontSize = 12.sp,
                )
            }
        } else if (!state.linkIsFast) {
            Column(
                Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .background(Color(0xCC0A0E12), RoundedCornerShape(16.dp))
                        .border(1.dp, Color(0x24FFFFFF), RoundedCornerShape(16.dp))
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Picture is rough for a moment",
                            color = Ink.Text,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = Ink.Accent,
                            trackColor = Color(0x33FFFFFF),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Still on Bluetooth. It sharpens when Wi-Fi takes over.",
                            color = Ink.Muted,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }

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
                Chip("${health.framesShown} f")
                if (health.framesDropped > 0) Chip("${health.framesDropped} dropped")
            }
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

/**
 * Turns the TextureView's content by [degrees].
 *
 * The view is already sized to the rotated picture's aspect ratio, so after
 * rotating by a quarter turn the content's bounding box is the view's height by
 * its width; the scale puts it back inside the view. Without that step a
 * quarter turn leaves most of the picture outside the view.
 */
private fun applyRotation(view: TextureView, degrees: Int, width: Int, height: Int) {
    if (width == 0 || height == 0) return
    val matrix = Matrix()
    val cx = width / 2f
    val cy = height / 2f
    // Scale FIRST, then rotate. A TextureView always draws its content
    // stretched to its own bounds, so for a quarter turn the content must be
    // stretched into a height-by-width rectangle *before* being turned, after
    // which it lands exactly in the view. Rotating first and scaling afterwards
    // applies the scale along the already-turned axes and squeezes the picture
    // into a strip — which is what it did.
    if (degrees % 180 == 90) {
        matrix.setScale(height.toFloat() / width, width.toFloat() / height, cx, cy)
    }
    matrix.postRotate(degrees.toFloat(), cx, cy)
    view.setTransform(matrix)
}
