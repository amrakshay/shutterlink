package dev.amrakshay.shutterlink.ui

import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.amrakshay.shutterlink.camera.CameraFacts
import dev.amrakshay.shutterlink.camera.CameraSession
import dev.amrakshay.shutterlink.camera.PhotoStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val TIMERS = listOf(0, 3, 10)

/**
 * The standalone camera: ShutterLink with nothing on the other end yet.
 *
 * Phase 1. Everything here is driven locally; from Phase 4 the same controls are
 * driven by messages arriving from the controller phone, which is why the state
 * is kept in one place rather than scattered through the widgets.
 */
@Composable
fun CameraScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val session = remember { CameraSession(context) }
    val photos = remember { PhotoStore(context) }
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }

    var front by remember { mutableStateOf(false) }
    var facts by remember { mutableStateOf<CameraFacts?>(null) }
    var zoom by remember { mutableStateOf(1f) }
    var timer by remember { mutableStateOf(0) }
    var countdown by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var lastShot by remember { mutableStateOf<Uri?>(null) }
    var thumb by remember { mutableStateOf<Bitmap?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(front) {
        problem = null
        try {
            facts = session.bind(lifecycleOwner, previewView, front)
            zoom = 1f
            session.setZoom(1f)
        } catch (t: Throwable) {
            problem = t.message ?: "The camera would not start"
        }
    }

    // The activity is locked to portrait, so the display rotation never changes —
    // which means CameraX would tag every capture as portrait however the phone
    // is actually being held. A camera that gets propped up WILL be turned on its
    // side, so the physical orientation has to come from the sensor instead.
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

    LaunchedEffect(lastShot) {
        val uri = lastShot ?: return@LaunchedEffect
        thumb = runCatching {
            context.contentResolver.loadThumbnail(uri, Size(240, 240), null)
        }.getOrNull()
    }

    fun shoot() {
        if (busy) return
        val capture = session.imageCapture ?: return
        pending = scope.launch {
            busy = true
            try {
                for (remaining in timer downTo 1) {
                    countdown = remaining
                    delay(1000)
                }
                countdown = null
                lastShot = photos.capture(capture, mirrored = front)
            } catch (t: Throwable) {
                problem = t.message ?: "That shot did not save"
            } finally {
                countdown = null
                busy = false
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {

        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(facts) {
                    val limits = facts ?: return@pointerInput
                    detectTransformGestures { _, _, change, _ ->
                        if (change == 1f) return@detectTransformGestures
                        zoom = (zoom * change).coerceIn(limits.minZoom, limits.maxZoom)
                        session.setZoom(zoom)
                    }
                },
        )

        TopStrip(zoom = zoom, onBack = onBack, modifier = Modifier.align(Alignment.TopCenter))

        countdown?.let { Countdown(it, Modifier.align(Alignment.Center)) }

        problem?.let { Problem(it, Modifier.align(Alignment.Center)) }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            facts?.let { ZoomPills(it, zoom) { picked -> zoom = picked; session.setZoom(picked) } }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LastShot(thumb)
                Shutter(enabled = !busy, onClick = ::shoot)
                FlipButton(
                    enabled = facts?.let { it.hasFrontCamera && it.hasBackCamera } == true,
                    onClick = { front = !front },
                )
            }

            TimerChip(timer) { timer = TIMERS[(TIMERS.indexOf(timer) + 1) % TIMERS.size] }
        }
    }
}

@Composable
private fun TopStrip(zoom: Float, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Pill(text = "Back", onClick = onBack)
        Pill(text = "%.1f×".format(zoom))
    }
}

@Composable
private fun Pill(text: String, onClick: (() -> Unit)? = null) {
    Box(
        modifier = Modifier
            .background(Color(0xB80A0E12), CircleShape)
            .border(1.dp, Color(0x1FFFFFFF), CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(text, color = Ink.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ZoomPills(facts: CameraFacts, current: Float, onPick: (Float) -> Unit) {
    val stops = listOf(0.6f, 1f, 2f, 5f).filter { it in facts.minZoom..facts.maxZoom }
    if (stops.size < 2) return

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        stops.forEach { stop ->
            val on = kotlin.math.abs(current - stop) < 0.05f
            Box(
                modifier = Modifier
                    .background(if (on) Ink.Accent else Color(0xB80A0E12), CircleShape)
                    .border(1.dp, if (on) Ink.Accent else Color(0x24FFFFFF), CircleShape)
                    .clickable { onPick(stop) }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            ) {
                Text(
                    if (stop < 1f) "${stop}×" else "${stop.toInt()}×",
                    color = if (on) Ink.AccentInk else Ink.Muted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun Shutter(enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(78.dp)
            .semantics { contentDescription = "Take photo" }
            .border(4.dp, Color(0x52FFFFFF), CircleShape)
            .padding(6.dp)
            .background(if (enabled) Color.White else Color(0x80FFFFFF), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
    )
}

@Composable
private fun FlipButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(54.dp)
            .background(Color(0xB80A0E12), CircleShape)
            .border(1.dp, Color(0x24FFFFFF), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        FlipGlyph(tint = if (enabled) Ink.Text else Ink.Faint)
    }
}

@Composable
private fun LastShot(thumb: Bitmap?) {
    Box(
        modifier = Modifier
            .size(54.dp)
            .background(Ink.Surface, RoundedCornerShape(14.dp))
            .border(1.dp, Color(0x38FFFFFF), RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (thumb != null) {
            androidx.compose.foundation.Image(
                bitmap = thumb.asImageBitmap(),
                contentDescription = "Last shot",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().padding(1.dp),
            )
        }
    }
}

@Composable
private fun TimerChip(seconds: Int, onCycle: () -> Unit) {
    Box(
        modifier = Modifier.clickable(onClick = onCycle).padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = if (seconds == 0) "Timer off" else "${seconds}s timer",
            color = if (seconds == 0) Ink.Muted else Ink.Accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun Countdown(value: Int, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(180.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(color = Color(0x38FFFFFF), style = Stroke(3.dp.toPx()))
        }
        Text(
            text = value.toString(),
            color = Color.White,
            fontSize = 96.sp,
            fontWeight = FontWeight.Light,
        )
    }
}

@Composable
private fun Problem(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .padding(32.dp)
            .background(Ink.SurfaceDim, RoundedCornerShape(16.dp))
            .border(1.dp, Ink.Line, RoundedCornerShape(16.dp))
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("The camera stopped", color = Ink.Text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Spacer(Modifier.height(6.dp))
        Text(text, color = Ink.Muted, fontSize = 13.sp)
    }
}

/** Two arcs chasing each other — the flip-camera mark from the mocks. */
@Composable
private fun FlipGlyph(tint: Color) {
    Canvas(Modifier.size(24.dp)) {
        val stroke = Stroke(width = 1.9.dp.toPx(), cap = StrokeCap.Round)
        val inset = 3.dp.toPx()
        val box = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2)
        val at = androidx.compose.ui.geometry.Offset(inset, inset)

        drawArc(tint, 170f, 120f, false, at, box, style = stroke)
        drawArc(tint, 350f, 120f, false, at, box, style = stroke)

        // Arrow heads, so it reads as rotation rather than as a broken ring.
        val tip = 3.2.dp.toPx()
        val right = androidx.compose.ui.geometry.Offset(size.width - inset, size.height / 2)
        val left = androidx.compose.ui.geometry.Offset(inset, size.height / 2)
        drawLine(tint, right, right.copy(x = right.x - tip, y = right.y - tip), stroke.width, StrokeCap.Round)
        drawLine(tint, right, right.copy(x = right.x - tip, y = right.y + tip), stroke.width, StrokeCap.Round)
        drawLine(tint, left, left.copy(x = left.x + tip, y = left.y - tip), stroke.width, StrokeCap.Round)
        drawLine(tint, left, left.copy(x = left.x + tip, y = left.y + tip), stroke.width, StrokeCap.Round)
    }
}
