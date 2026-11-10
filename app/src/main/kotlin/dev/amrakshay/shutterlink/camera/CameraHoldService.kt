package dev.amrakshay.shutterlink.camera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.amrakshay.shutterlink.MainActivity
import dev.amrakshay.shutterlink.R

private const val CHANNEL = "camera_in_use"
private const val NOTIFICATION_ID = 1

/**
 * Keeps the camera alive while the camera phone's screen is off.
 *
 * Required, not a convenience: without a foreground service Android stops the
 * camera within seconds of the screen going dark, and the whole point of this
 * phone is that it sits across the room untouched. It also has to dim its own
 * screen so it does not light the subject, which is the same thing from
 * Android's point of view as being abandoned.
 *
 * The notification is not something to be apologised for or hidden. A phone
 * quietly holding a live camera should say so, where its owner can see it and
 * stop it in one tap.
 */
class CameraHoldService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        // START_NOT_STICKY: if Android kills this, the connection is gone too,
        // and silently restarting a camera the user cannot see would be worse
        // than stopping.
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    "Camera in use",
                    // LOW: it must be visible and dismissible-proof, but it
                    // should never make a sound during someone's photo.
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shown while this phone is acting as the camera."
                    setShowBadge(false)
                },
            )
        }

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("ShutterLink is using the camera")
            .setContentText("Your other phone is controlling it. Tap to stop.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(open)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // From API 29 the type has to be declared at start time as well as
            // in the manifest, or the service is refused outright.
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, CameraHoldService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CameraHoldService::class.java))
        }
    }
}
