package dev.amrakshay.shutterlink.camera

import android.content.Context
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import android.view.Surface
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
     * Binds the camera with its preview going straight into [encoderSurface].
     *
     * No local preview while streaming: the camera phone is propped up and about
     * to have its screen dimmed anyway, and feeding the encoder directly means
     * no frame is ever copied through the CPU. Going via ImageAnalysis and
     * converting YUV by hand cannot hold 24fps at 720p on this class of phone.
     */
    suspend fun bindForStreaming(
        lifecycleOwner: LifecycleOwner,
        encoderSurface: Surface,
        front: Boolean,
        onRotation: (Int) -> Unit = {},
    ): CameraFacts {
        val provider = provider()

        val preview = Preview.Builder().build().also { preview ->
            preview.surfaceProvider = Preview.SurfaceProvider { request ->
                // CameraX works out how the sensor's output relates to the way
                // the phone is held. Feeding a raw Surface means nothing applies
                // that for us, so the angle is passed on to whoever displays it.
                request.setTransformationInfoListener(
                    ContextCompat.getMainExecutor(context),
                ) { info -> onRotation(info.rotationDegrees) }
                request.provideSurface(
                    encoderSurface,
                    ContextCompat.getMainExecutor(context),
                ) {
                    // The encoder owns this Surface and releases it in stop();
                    // releasing it here as well would crash the next bind.
                }
            }
        }
        val capture = ImageCapture.Builder()
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

    fun release() {
        provider?.unbindAll()
        camera = null
        imageCapture = null
    }
}
