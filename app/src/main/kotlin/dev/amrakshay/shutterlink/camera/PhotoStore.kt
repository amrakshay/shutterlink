package dev.amrakshay.shutterlink.camera

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
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

    fun newName(): String =
        "SHUTTERLINK_" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".jpg"

    /**
     * Captures to a private file instead of straight to the gallery.
     *
     * Needed because the photo has to be sent to the other phone, and the
     * transport needs a real File. It is also the shape §11.1 of the plan needs:
     * capture somewhere private first, then decide whether the gallery already
     * has a copy before adding one, so a third-party camera app that saves its
     * own cannot leave the user with two.
     */
    suspend fun captureToFile(
        imageCapture: ImageCapture,
        mirrored: Boolean,
        name: String = newName(),
    ): File = suspendCancellableCoroutine { cont ->
        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        val file = File(dir, name)
        val options = ImageCapture.OutputFileOptions.Builder(file)
            .setMetadata(ImageCapture.Metadata().apply { isReversedHorizontal = mirrored })
            .build()

        imageCapture.takePicture(
            options,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    cont.resume(file)
                }

                override fun onError(exception: ImageCaptureException) {
                    cont.resumeWithException(exception)
                }
            },
        )
    }

    /**
     * Copies a captured file into the gallery.
     *
     * Separate from [captureToFile] so the caller can decide: ShutterLink's own
     * shots always go in, but a shot taken by another camera app may already be
     * there, and adding a second copy is worse than adding none.
     */
    fun publish(file: File, name: String = file.name): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, ALBUM)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values,
        ) ?: return null

        context.contentResolver.openOutputStream(uri).use { out ->
            if (out == null) return null
            file.inputStream().use { it.copyTo(out) }
        }
        context.contentResolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
            null,
            null,
        )
        return uri
    }

    /** Throw away a cached capture once it has been published and sent. */
    fun discard(file: File) {
        runCatching { file.delete() }
    }

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
