package dev.amrakshay.shutterlink.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import android.view.Surface
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG_SESSION = "ShutterLink/Camera"

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

        val selector =
            if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

        val builder = Preview.Builder()
        pinFrameRate(provider, selector, builder)

        val preview = builder.build().also { preview ->
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

    /**
     * Makes the camera behave like a webcam rather than a stills camera.
     *
     * Left alone, Camera2's auto-exposure optimises for a good-looking picture:
     * in a dim room it lengthens the exposure, and a 300 ms exposure can only
     * ever produce three frames a second. Measured exactly that — 2.9 fps at
     * 720p, while the link sat idle at about 50 KB/s.
     *
     * Video-call apps look grainy in low light precisely because they do this:
     * pinning the frame rate caps the exposure, so the picture gets darker and
     * noisier instead of slower. For a viewfinder someone is framing a shot
     * with, smooth and dim beats bright and stuttering.
     *
     * The range is chosen from what the device reports. Asking for one it does
     * not support makes binding fail outright, and the highest **lower** bound
     * is what matters — a range like [15, 30] still lets AE drop to 15.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun pinFrameRate(
        provider: ProcessCameraProvider,
        selector: CameraSelector,
        builder: Preview.Builder,
    ) {
        val chosen = runCatching {
            val info = provider.getCameraInfo(selector)
            val ranges = Camera2CameraInfo.from(info)
                .getCameraCharacteristic(
                    CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES,
                )
                ?.toList()
                .orEmpty()
            // Highest floor first; among equals, the narrowest range, since a
            // wide one leaves AE free to wander back down.
            ranges
                .filter { it.upper >= 24 }
                .maxWithOrNull(
                    compareBy<Range<Int>>({ it.lower }, { -(it.upper - it.lower) }),
                )
        }.getOrNull()

        if (chosen == null) {
            Log.w(TAG_SESSION, "no usable AE fps range reported; leaving it to the camera")
            return
        }
        Log.i(TAG_SESSION, "pinning camera to $chosen fps")
        Camera2Interop.Extender(builder).setCaptureRequestOption(
            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
            chosen,
        )
    }

    fun release() {
        provider?.unbindAll()
        camera = null
        imageCapture = null
    }
}
