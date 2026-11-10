package dev.amrakshay.shutterlink.ui

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.amrakshay.shutterlink.transport.HidShutter
import dev.amrakshay.shutterlink.transport.ShutterKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val TIMERS = listOf(0, 3, 10)

/**
 * The whole app.
 *
 * ShutterLink is a Bluetooth shutter button. This phone pretends to be a
 * Bluetooth keyboard; your other phone pairs with it the same way it would
 * pair with a selfie remote, from its own Bluetooth settings; and the button
 * here sends a keystroke that whatever camera app is open over there treats as
 * its shutter.
 *
 * **Nothing is installed on the other phone.** That is the point of this
 * shape, and it is what every earlier version got wrong by trying to be
 * cleverer: there is no pairing protocol of ours, no discovery, no network, no
 * Play Services and no account. Two phones and a Bluetooth bond.
 *
 * The cost, accepted deliberately: no live view and no photo coming back. You
 * walk over and look at the other phone. Everything that tried to avoid that
 * walk — streaming a viewfinder, sending the photo across — needed a fast
 * local network, and the whole point is that this works in a field.
 */
@Composable
fun RemoteScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val hid = remember { HidShutter(context) }
    var registered by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(false) }
    var hostName by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var timer by remember { mutableStateOf(0) }
    var countdown by remember { mutableStateOf<Int?>(null) }
    var justFired by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf(ShutterKey.VOLUME_UP) }
    var discoverableUntil by remember { mutableStateOf(0L) }

    val myName = remember { bluetoothName(context) }

    val askDiscoverable = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val seconds = result.resultCode
        if (seconds > 0) {
            discoverableUntil = System.currentTimeMillis() + seconds * 1000L
        } else {
            problem = "Visibility was declined, so the other phone cannot find this one."
        }
    }

    // Become a keyboard. Being registered is not the same as having something
    // listening — the Samsung reported registered=true while the shutter did
    // nothing, because no host had attached — so both are tracked and shown.
    LaunchedEffect(Unit) {
        if (!hid.supported()) {
            problem = "This phone has no Bluetooth"
            return@LaunchedEffect
        }
        hid.onHostChanged = { ok ->
            connected = ok
            hostName = hid.hostName()
        }
        hid.register { ok ->
            registered = ok
            if (!ok) problem = "This phone will not act as a Bluetooth keyboard"
        }
    }

    // A phone that bonded while we were not looking should not need the app
    // restarted. Watch for bonds appearing and attach to them.
    DisposableEffect(registered) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val device: BluetoothDevice? =
                    intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                if (device != null) hid.connectTo(device)
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    // Attach to anything already paired, so a second session needs no setup.
    LaunchedEffect(registered) {
        if (!registered) return@LaunchedEffect
        while (!connected) {
            hid.pairedDevices().forEach { hid.connectTo(it) }
            delay(2000)
        }
    }

    DisposableEffect(Unit) { onDispose { hid.release() } }

    fun fire() {
        scope.launch {
            problem = null
            for (left in timer downTo 1) {
                countdown = left
                delay(1000)
            }
            countdown = null
            if (hid.press(key)) {
                justFired = true
                delay(1200)
                justFired = false
            } else {
                problem = "The keystroke did not go through. Is the other phone still paired?"
            }
        }
    }

    KeepScreenOn()

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
                .background(if (connected) Ink.AccentWell else Ink.SurfaceDim, CircleShape)
                .border(1.dp, if (connected) Ink.Positive else Ink.Line, CircleShape)
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(8.dp)
                    .background(if (connected) Ink.Positive else Ink.Faint, CircleShape),
            )
            Spacer(Modifier.width(9.dp))
            Text(
                when {
                    connected -> "Connected to ${hostName ?: "your other phone"}"
                    registered -> "Waiting for your other phone"
                    else -> "Starting up"
                },
                color = if (connected) Ink.Positive else Ink.Faint,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Spacer(Modifier.height(36.dp))

        if (!connected) {
            SetupPanel(
                myName = myName,
                discoverable = discoverableUntil > System.currentTimeMillis(),
                onMakeVisible = {
                    problem = null
                    runCatching {
                        askDiscoverable.launch(
                            Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                            },
                        )
                    }.onFailure { problem = "Could not ask to be visible" }
                },
            )
        } else {
            Text(
                countdown?.toString() ?: if (justFired) "Taken" else "Ready",
                color = if (justFired) Ink.Positive else Ink.Text,
                fontSize = if (countdown != null) 96.sp else 26.sp,
                fontWeight = if (countdown != null) FontWeight.Light else FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Open any camera app on your other phone and point it where you " +
                    "want. This button is its shutter.",
                color = Ink.Muted,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                textAlign = TextAlign.Center,
            )
        }

        problem?.let {
            Spacer(Modifier.height(18.dp))
            Text(it, color = Ink.Warning, fontSize = 13.sp, textAlign = TextAlign.Center)
        }

        Spacer(Modifier.weight(1f))

        Box(
            Modifier
                .size(104.dp)
                .border(4.dp, if (connected) Color(0x66FFFFFF) else Color(0x22FFFFFF), CircleShape)
                .padding(8.dp)
                .background(
                    if (connected && countdown == null) Color.White else Color(0x55FFFFFF),
                    CircleShape,
                )
                .clickable(enabled = connected && countdown == null) { fire() },
        )

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (timer == 0) "Timer off" else "${timer}s timer",
                color = if (timer == 0) Ink.Muted else Ink.Accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable { timer = TIMERS[(TIMERS.indexOf(timer) + 1) % TIMERS.size] }
                    .padding(10.dp),
            )
            Spacer(Modifier.width(8.dp))
            // Which key to send is a setting, not a constant, because not every
            // camera app honours the same one. Measured on the OnePlus stock
            // camera: Volume up, Volume down, Camera, Enter and D-pad centre all
            // take a photo; Headset hook and Focus do not.
            Text(
                "Sends ${key.label}",
                color = Ink.Muted,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable {
                        val all = ShutterKey.entries
                        key = all[(all.indexOf(key) + 1) % all.size]
                    }
                    .padding(10.dp),
            )
        }
    }
}

@Composable
private fun SetupPanel(myName: String?, discoverable: Boolean, onMakeVisible: () -> Unit) {
    Text(
        "Pair your other phone",
        color = Ink.Text,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(12.dp))
    Text(
        "This phone is acting as a Bluetooth keyboard. Pair with it from the " +
            "other phone, exactly as you would pair a selfie remote — there is " +
            "nothing to install over there.",
        color = Ink.Muted,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(20.dp))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Ink.Surface, RoundedCornerShape(16.dp))
            .border(1.dp, Ink.Line, RoundedCornerShape(16.dp))
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("ON THE OTHER PHONE, PAIR WITH", color = Ink.Faint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            myName ?: "this phone",
            color = Ink.Text,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }

    Spacer(Modifier.height(16.dp))
    Text(
        if (discoverable) {
            "Visible now. Open Bluetooth settings on your other phone and tap this name."
        } else {
            "Make this phone visible first — Android hides it otherwise, and the " +
                "other phone will not find it."
        },
        color = Ink.Faint,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(14.dp))
    Text(
        if (discoverable) "Still not showing? Make visible again" else "Make this phone visible",
        color = Ink.AccentInk,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .background(Ink.Accent, RoundedCornerShape(15.dp))
            .clickable(onClick = onMakeVisible)
            .padding(vertical = 15.dp),
    )
}

private fun bluetoothName(context: Context): String? = runCatching {
    @Suppress("MissingPermission")
    context.getSystemService(BluetoothManager::class.java)?.adapter?.name
}.getOrNull()
