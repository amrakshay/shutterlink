package dev.amrakshay.shutterlink.camera

import android.content.Context
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What the bound camera can do, read once rather than guessed at. */
data class CameraFacts(
    val minZoom: Float,
    val maxZoom: Float,
    val hasFrontCamera: Boolean,
    val hasBackCamera: Boolean,
)

/**
 * Binds CameraX to a [PreviewView] and keeps hold of what the UI needs.
 *
 * All of this will be driven remotely in Phase 3 onwards, so the binding is kept
 * out of the composable: the screen says what it wants, this says how.
 */
class CameraSession(private val context: Context) {

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null

    var imageCapture: ImageCapture? = null
        private set

    /** Awaits the provider. CameraX hands back a ListenableFuture, not a coroutine. */
    private suspend fun provider(): ProcessCameraProvider =
        provider ?: suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    try {
                        cont.resume(future.get().also { provider = it })
                    } catch (t: Throwable) {
                        cont.resumeWithException(t)
                    }
                },
                ContextCompat.getMainExecutor(context),
            )
        }

    /**
     * (Re)binds the camera. Called again on every flip — CameraX requires an
     * unbindAll before binding a different selector, so there is no cheaper path.
     */
    suspend fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        front: Boolean,
    ): CameraFacts {
        val provider = provider()

        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }
        val capture = ImageCapture.Builder()
            // The point of this app is framing, not speed: favour the better
            // image over a faster shutter.
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()

        val selector =
            if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

        provider.unbindAll()
        val bound = provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)

        camera = bound
        imageCapture = capture

        val zoom = bound.cameraInfo.zoomState.value
        return CameraFacts(
            minZoom = zoom?.minZoomRatio ?: 1f,
            maxZoom = zoom?.maxZoomRatio ?: 1f,
            hasFrontCamera = provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA),
            hasBackCamera = provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA),
        )
    }

    fun setZoom(ratio: Float) {
        camera?.cameraControl?.setZoomRatio(ratio)
    }

    /**
     * 0..1 across whatever range this camera has.
     *
     * The controller sends linear zoom rather than a ratio precisely so it never
     * has to know the far camera's range — a 5x on one phone is past the end of
     * another's.
     */
    fun setLinearZoom(value: Float) {
        camera?.cameraControl?.setLinearZoom(value.coerceIn(0f, 1f))
    }

    /**
     * Binds the camera for stills only — no preview anywhere.
     *
     * The remote phone has no viewfinder to feed and this phone is propped up
     * with its screen dark, so a Preview use case would spend the battery
     * producing frames for nobody. CameraX is happy to bind ImageCapture on its
     * own, and the camera then sits idle between shots instead of running at
     * thirty frames a second.
     *
     * Nothing here pins the frame rate. That existed to stop auto-exposure
     * lengthening the exposure until the stream crawled, and for a still it is
     * exactly the wrong thing to do: a long exposure is how a stills camera
     * gets a clean picture in a dim room.
     */
    suspend fun bindForCapture(
        lifecycleOwner: LifecycleOwner,
        front: Boolean,
    ): CameraFacts {
        val provider = provider()

        val selector =
            if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()

        provider.unbindAll()
        val bound = provider.bindToLifecycle(lifecycleOwner, selector, capture)
        camera = bound
        imageCapture = capture

        val zoom = bound.cameraInfo.zoomState.value
        return CameraFacts(
            minZoom = zoom?.minZoomRatio ?: 1f,
            maxZoom = zoom?.maxZoomRatio ?: 1f,
            hasFrontCamera = provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA),
            hasBackCamera = provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA),
        )
    }

    fun release() {
        provider?.unbindAll()
        camera = null
        imageCapture = null
    }
}
