package dev.amrakshay.shutterlink.camera

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.MediaStore
import dev.amrakshay.shutterlink.protocol.CaptureEngine
import dev.amrakshay.shutterlink.protocol.ENGINE_SHUTTERLINK

/**
 * Which camera apps this phone has, and which of them will hand a photo back.
 *
 * Verified on the OnePlus 7 this was built against: `com.oneplus.camera`
 * answers both intents below. PicsArt was not installed there and is untested —
 * which is why [CaptureEngine.tested] exists rather than the app pretending to
 * know.
 */
object CaptureEngines {

    /** Apps that can be launched as a camera at all. */
    private val launchable = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)

    /**
     * Apps that accept launch-for-result and return the photo directly.
     *
     * This is the difference between the clean path and the messy one. An app
     * that answers this hands both the photo and control back by itself; one
     * that does not needs the gallery watched and an overlay permission to get
     * the user back to ShutterLink afterwards.
     */
    private val handsBack = Intent(MediaStore.ACTION_IMAGE_CAPTURE)

    /** Packages confirmed to work by hand. Everything else is flagged untested. */
    private val known = setOf("com.oneplus.camera")

    fun discover(context: Context): List<CaptureEngine> {
        val pm = context.packageManager
        val self = context.packageName

        val canLaunch = pm.queryIntentActivities(launchable, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .filterNot { it == self }
            .toSet()

        val canHandBack = pm.queryIntentActivities(handsBack, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .filterNot { it == self }
            .toSet()

        val others = (canLaunch + canHandBack).sorted().map { pkg ->
            CaptureEngine(
                id = pkg,
                label = label(pm, pkg),
                handsPhotoBack = pkg in canHandBack,
                tested = pkg in known,
            )
        }

        // Ours always first, and always the one with a live preview.
        return listOf(
            CaptureEngine(
                id = ENGINE_SHUTTERLINK,
                label = "ShutterLink",
                handsPhotoBack = true,
                tested = true,
            ),
        ) + others
    }

    private fun label(pm: PackageManager, pkg: String): String = runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrElse {
        // A tidied package name beats showing the raw one.
        pkg.substringAfterLast('.').replaceFirstChar { c -> c.uppercase() }
    }
}
