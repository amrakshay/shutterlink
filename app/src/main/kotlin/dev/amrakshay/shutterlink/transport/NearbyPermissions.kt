package dev.amrakshay.shutterlink.transport

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * What finding the other phone costs in permissions, which is not the same on
 * any two Android versions.
 *
 * This is the single most likely thing to break on a device we have not tried,
 * and the most likely thing to be refused by a user who was not told why — so
 * the list is derived from the running version rather than declared once and
 * hoped for.
 *
 * - **Android 10–11** need `ACCESS_FINE_LOCATION` for *any* BLE scan and for
 *   Wi-Fi Direct discovery. The app never reads a location and does not want
 *   one; Android simply will not scan without it. This surprises people, so the
 *   UI says so in as many words.
 * - **Android 12+** replace that with the explicit Bluetooth permissions.
 * - **Android 13+** add `NEARBY_WIFI_DEVICES` for the Wi-Fi side.
 *
 * Android 12 is the awkward one: it has the new Bluetooth permissions but
 * Nearby still wants fine location for the Wi-Fi half, so both are asked for.
 */
object NearbyPermissions {

    fun required(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            // Below 33 there is no nearby-devices permission, and the Wi-Fi and
            // BLE scans are both gated on location instead.
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun missing(context: Context): List<String> = required().filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }

    fun allGranted(context: Context): Boolean = missing(context).isEmpty()

    /**
     * Why we are asking, in the user's terms rather than Android's. Shown next
     * to each request — a permission sheet with no reason beside it is the one
     * people refuse.
     */
    fun reason(permission: String): String = when (permission) {
        Manifest.permission.ACCESS_FINE_LOCATION ->
            "Android 10 and 11 refuse to scan for nearby phones without it. " +
                "ShutterLink never reads your location."
        Manifest.permission.NEARBY_WIFI_DEVICES ->
            "To send the live view over Wi-Fi Direct, straight to your other phone."
        Manifest.permission.BLUETOOTH_SCAN -> "To find your other phone."
        Manifest.permission.BLUETOOTH_ADVERTISE -> "So your other phone can find this one."
        Manifest.permission.BLUETOOTH_CONNECT -> "To connect to it once you have picked it."
        else -> "Needed to reach your other phone."
    }

    fun label(permission: String): String = when (permission) {
        Manifest.permission.ACCESS_FINE_LOCATION -> "Location"
        Manifest.permission.NEARBY_WIFI_DEVICES -> "Nearby Wi-Fi devices"
        Manifest.permission.BLUETOOTH_SCAN -> "Find nearby devices"
        Manifest.permission.BLUETOOTH_ADVERTISE -> "Be discoverable"
        Manifest.permission.BLUETOOTH_CONNECT -> "Connect to devices"
        else -> permission.substringAfterLast('.')
    }
}
