package com.retro.vhs.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import android.util.Size
import com.retro.vhs.vhs.InputTransform

/**
 * CameraX supplies frames straight into the render thread's SurfaceTexture; nothing
 * else is bound, because the recording path is our own encoder.
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onTransform: (InputTransform) -> Unit,
    private val onError: (String) -> Unit
) {

    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var camera: Camera? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var providedSurface: Surface? = null

    var lensFacing: Int = CameraSelector.LENS_FACING_BACK
        private set

    /**
     * Rotation of the window the viewfinder is drawn in. Remembered here because bind()
     * happens asynchronously: setting it straight onto [preview] would be lost whenever
     * the use case does not exist yet, which is exactly the case at startup.
     */
    private var targetRotation: Int = Surface.ROTATION_0

    /** Live rotation numbers, surfaced in the setup menu to diagnose odd devices. */
    var diagnostics: String = "camera not started"
        private set

    val hasFrontCamera: Boolean
        get() = provider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) == true

    /** Every camera the device reports, with the orientation that drives the rotation. */
    fun hardwareReport(): String = buildString {
        val bound = camera?.cameraInfo
        appendLine("bound lens       : ${if (lensFacing == CameraSelector.LENS_FACING_FRONT) "front" else "back"}")
        appendLine("bound sensor rot : ${bound?.sensorRotationDegrees ?: -1}°")
        appendLine("target rotation  : ${targetRotation * 90}° (Surface.ROTATION_$targetRotation)")
        appendLine("camerax says     : $diagnostics")
        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            manager.cameraIdList.forEach { id ->
                val characteristics = manager.getCameraCharacteristics(id)
                val facing = when (characteristics.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "front"
                    CameraCharacteristics.LENS_FACING_BACK -> "back"
                    else -> "external"
                }
                val orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
                appendLine("camera2 id $id     : $facing, SENSOR_ORIENTATION $orientation°")
            }
        } catch (t: Throwable) {
            appendLine("camera2 enumeration failed: ${t.message}")
        }
    }

    fun start(surfaceTexture: SurfaceTexture) {
        this.surfaceTexture = surfaceTexture
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                provider = future.get()
                bind()
            } catch (t: Throwable) {
                Log.e(TAG, "camera provider failed", t)
                onError(t.message ?: "camera unavailable")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun switchCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        bind()
    }

    fun updateTargetRotation(rotation: Int) {
        if (rotation == targetRotation && preview?.targetRotation == rotation) return
        targetRotation = rotation
        // Updating the live use case re-delivers TransformationInfo, which is what
        // tells the renderer how much to turn the camera buffer.
        preview?.targetRotation = rotation
    }

    fun stop() {
        provider?.unbindAll()
        providedSurface?.release()
        providedSurface = null
    }

    private fun bind() {
        val provider = provider ?: return
        val texture = surfaceTexture ?: return
        val executor = ContextCompat.getMainExecutor(context)

        provider.unbindAll()

        // Ask for a 4:3 sensor read-out: that is the shape a 1980s camera actually saw.
        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(1280, 960),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()

        val newPreview = Preview.Builder()
            .setResolutionSelector(resolutionSelector)
            .setTargetRotation(targetRotation)
            .build()

        val mirror = lensFacing == CameraSelector.LENS_FACING_FRONT

        newPreview.setSurfaceProvider(executor) { request ->
            val resolution = request.resolution
            texture.setDefaultBufferSize(resolution.width, resolution.height)

            request.setTransformationInfoListener(executor) { info ->
                val degrees = ((info.rotationDegrees % 360) + 360) % 360
                diagnostics = "sensor ${camera?.cameraInfo?.sensorRotationDegrees ?: -1}° · " +
                    "target ${targetRotation * 90}° · camerax ${degrees}° · " +
                    "${resolution.width}x${resolution.height}"
                onTransform(
                    InputTransform(
                        rotationDegrees = degrees,
                        mirror = mirror,
                        sourceWidth = resolution.width,
                        sourceHeight = resolution.height
                    )
                )
            }

            val surface = Surface(texture)
            providedSurface = surface
            request.provideSurface(surface, executor) { result ->
                surface.release()
                if (providedSurface === surface) providedSurface = null
                Log.d(TAG, "surface released, result ${result.resultCode}")
            }
        }

        try {
            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            camera = provider.bindToLifecycle(lifecycleOwner, selector, newPreview)
            preview = newPreview
        } catch (t: Throwable) {
            Log.e(TAG, "bindToLifecycle failed", t)
            onError(t.message ?: "could not open camera")
        }
    }

    companion object {
        private const val TAG = "CameraController"
    }
}
