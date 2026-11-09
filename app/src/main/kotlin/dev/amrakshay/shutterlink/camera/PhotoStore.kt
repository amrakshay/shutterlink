package dev.amrakshay.shutterlink.camera

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val ALBUM = "DCIM/ShutterLink"

/**
 * Writes captures into the shared photo collection, so they appear in the
 * gallery like any other photo.
 *
 * Deliberately MediaStore rather than app-private storage plus a copy later:
 * from API 29 on, inserting here needs no storage permission at all, and the
 * photo belongs to the user rather than to the app — uninstalling does not take
 * their pictures with it.
 */
class PhotoStore(private val context: Context) {

    /**
     * Takes the photo and returns where it landed.
     *
     * [mirrored] should be true for the front camera, so the saved file matches
     * the preview the person was looking at rather than being flipped.
     */
    suspend fun capture(imageCapture: ImageCapture, mirrored: Boolean): Uri =
        suspendCancellableCoroutine { cont ->
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "SHUTTERLINK_$stamp.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, ALBUM)
                // IS_PENDING hides the row until the bytes are actually written,
                // so the gallery never shows a half-written file.
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val options = ImageCapture.OutputFileOptions
                .Builder(
                    context.contentResolver,
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values,
                )
                .setMetadata(ImageCapture.Metadata().apply { isReversedHorizontal = mirrored })
                .build()

            imageCapture.takePicture(
                options,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        val uri = output.savedUri
                        if (uri == null) {
                            cont.resumeWithException(
                                IllegalStateException("capture reported success with no uri"),
                            )
                            return
                        }
                        context.contentResolver.update(
                            uri,
                            ContentValues().apply {
                                put(MediaStore.Images.Media.IS_PENDING, 0)
                            },
                            null,
                            null,
                        )
                        cont.resume(uri)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        cont.resumeWithException(exception)
                    }
                },
            )
        }
}
