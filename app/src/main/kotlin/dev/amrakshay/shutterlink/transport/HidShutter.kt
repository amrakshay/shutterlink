package dev.amrakshay.shutterlink.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import dev.amrakshay.shutterlink.hid.HidKeyboard
import dev.amrakshay.shutterlink.hid.ShutterKey
import java.util.concurrent.Executors

private const val TAG = "ShutterLink/HID"

/**
 * Pretends to be a Bluetooth keyboard so any camera app can be fired.
 *
 * This is how commercial selfie remotes actually work, and it is the only way
 * to trigger an app we do not control: a third-party camera app's shutter is
 * inside its own sandbox and nothing can reach it — but a keystroke goes to
 * whatever is in the foreground, because that is what keyboards do, and
 * virtually every camera app maps volume keys to the shutter.
 *
 * Verified only so far as the OnePlus 7 declaring `HidDeviceService` in its
 * Bluetooth stack. Whether a given phone will actually register as an HID
 * device, and whether a given camera app honours the key, are both untested —
 * OEM support is inconsistent and this is the part most likely to need a
 * fallback key. Hence [ShutterKey] being a choice rather than a constant.
 */
@SuppressLint("MissingPermission")
class HidShutter(private val context: Context) {

    private val executor = Executors.newSingleThreadExecutor()
    private var proxy: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null

    var registered: Boolean = false
        private set

    /**
     * True when a host has actually attached, which is the only state in which
     * a keystroke goes anywhere.
     *
     * Distinct from [registered] on purpose, and the distinction cost real
     * confusion: the Samsung reported `registered=true, plugged=false` and the
     * shutter silently did nothing, because registering as a keyboard and
     * having something listening are two different things.
     */
    val connected: Boolean get() = host != null

    /** Called whenever [connected] changes, so a screen can follow it. */
    var onHostChanged: ((Boolean) -> Unit)? = null

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            this@HidShutter.registered = registered
            // This callback carries the host, and ignoring it cost a bug worth
            // remembering: after an app restart with the phones already
            // bonded, the host attaches during registration and
            // onConnectionStateChanged never reports CONNECTED, because from
            // the stack's point of view nothing changed. The app sat on its
            // setup screen with a working keyboard behind it.
            if (pluggedDevice != null) {
                host = pluggedDevice
                connecting = false
                prime()
            }
            Log.i(TAG, "HID app registered=$registered plugged=${pluggedDevice != null}")
            onHostChanged?.invoke(connected)
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                host = device
                prime()
            }
            if (state == BluetoothProfile.STATE_DISCONNECTED && device == host) host = null
            connecting = state == BluetoothProfile.STATE_CONNECTING
            Log.i(TAG, "HID connection state=$state, connected=$connected")
            onHostChanged?.invoke(connected)
        }
    }

    /** True when this phone can act as an HID device at all. */
    fun supported(): Boolean =
        context.getSystemService(BluetoothManager::class.java)?.adapter != null

    /**
     * Register as a keyboard. [onReady] fires once the profile proxy is up,
     * which is not the same as being connected to anything.
     */
    fun register(onReady: (Boolean) -> Unit) {
        val adapter: BluetoothAdapter? =
            context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            onReady(false)
            return
        }

        adapter.getProfileProxy(
            context,
            object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, p: BluetoothProfile?) {
                    if (profile != BluetoothProfile.HID_DEVICE) return
                    val hid = p as? BluetoothHidDevice
                    if (hid == null) {
                        onReady(false)
                        return
                    }
                    proxy = hid
                    val sdp = BluetoothHidDeviceAppSdpSettings(
                        "ShutterLink",
                        "Remote shutter",
                        "amrakshay",
                        BluetoothHidDevice.SUBCLASS1_KEYBOARD,
                        HidKeyboard.REPORT_DESCRIPTOR,
                    )
                    val ok = hid.registerApp(sdp, null, null, executor, callback)
                    onReady(ok)
                }

                override fun onServiceDisconnected(profile: Int) {
                    if (profile == BluetoothProfile.HID_DEVICE) {
                        proxy = null
                        registered = false
                    }
                }
            },
            BluetoothProfile.HID_DEVICE,
        )
    }

    /** The attached host's name, for the UI to show. */
    fun hostName(): String? = runCatching { host?.name }.getOrNull()

    /**
     * Connect to a paired phone so keystrokes have somewhere to go.
     *
     * Refuses while an attempt is already in flight. Calling connect() again
     * on a connecting device aborts the first attempt, so a retry loop with no
     * guard never finishes one.
     */
    fun connectTo(device: BluetoothDevice): Boolean {
        if (connecting || host != null) return false
        connecting = true
        return proxy?.connect(device) == true
    }

    /** True while an attempt is in flight, so callers do not stamp on it. */
    var connecting: Boolean = false
        private set

    /**
     * Paired devices that could plausibly be the camera phone.
     *
     * Filtered, and that matters. The first version offered every bonded
     * device and the caller tried them all: headphones, a speaker, a TV
     * remote. Firing HID connects at a pair of earbuds every two seconds is
     * not harmless — it kept tearing down the attempt that was actually
     * progressing, and the state log cycled 1, 0, 1, 0 forever while the real
     * host sat there waiting.
     *
     * A phone or a computer can host a keyboard. An audio device cannot.
     */
    fun candidateHosts(): List<BluetoothDevice> =
        context.getSystemService(BluetoothManager::class.java)
            ?.adapter?.bondedDevices
            ?.filter {
                when (runCatching { it.bluetoothClass?.majorDeviceClass }.getOrNull()) {
                    android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO,
                    android.bluetooth.BluetoothClass.Device.Major.PERIPHERAL,
                    android.bluetooth.BluetoothClass.Device.Major.WEARABLE,
                    android.bluetooth.BluetoothClass.Device.Major.HEALTH,
                    -> false
                    else -> true
                }
            }
            ?.toList()
            .orEmpty()

    /**
     * Send one empty report, so the first real keystroke is not the first
     * report the host has ever seen.
     *
     * Measured: with everything warm the shutter fired four times out of four,
     * twice over. But the first press straight after the link came up — or
     * straight after the camera app launched — was dropped, repeatably. The
     * host appears to need a moment to finish setting up the input device, and
     * whatever arrives during it goes nowhere.
     *
     * An all-zero report is the one that says "nothing is held", so priming
     * with it cannot press anything by accident.
     */
    private fun prime() {
        val hid = proxy ?: return
        val to = host ?: return
        runCatching { hid.sendReport(to, 0, HidKeyboard.releaseReport()) }
    }

    /** Press and release a key. See [HidKeyboard.releaseReport] on the release. */
    fun press(key: ShutterKey): Boolean {
        val hid = proxy ?: return false
        val to = host ?: return false
        val sent = hid.sendReport(to, 0, HidKeyboard.pressReport(key))
        hid.sendReport(to, 0, HidKeyboard.releaseReport())
        return sent
    }

    fun release() {
        runCatching { proxy?.unregisterApp() }
        proxy = null
        host = null
        registered = false
        connecting = false
    }
}
