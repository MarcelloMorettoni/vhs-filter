package com.retro.vhs.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.retro.vhs.camera.CameraController
import com.retro.vhs.data.AppSettings
import com.retro.vhs.media.DebugReport
import com.retro.vhs.media.MediaStoreSaver
import com.retro.vhs.media.VideoFileProcessor
import com.retro.vhs.render.RecordRequest
import com.retro.vhs.render.VhsRenderThread
import com.retro.vhs.vhs.InputTransform
import com.retro.vhs.vhs.VhsPreset
import java.util.concurrent.Executors

class MainActivity : ComponentActivity(), VhsRenderThread.Callback {

    private val presets = VhsPreset.ALL
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var settings: AppSettings
    private var renderThread: VhsRenderThread? = null
    private var camera: CameraController? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var cameraPermitted = false
    private var audioPermitted = false

    private var pendingOutput: MediaStoreSaver.Output? = null
    private var processor: VideoFileProcessor? = null
    private var lastTransform: InputTransform? = null

    private var diagnostics by mutableStateOf("camera not started")

    // ---- compose state ----
    private var presetIndex by mutableIntStateOf(0)
    private var recording by mutableStateOf(false)
    private var elapsedSec by mutableIntStateOf(0)
    private var status by mutableStateOf<String?>(null)
    private var processing by mutableStateOf<ProcessingUiState?>(null)
    private var ui by mutableStateOf(
        SettingsUiState(true, true, true, true, true, com.retro.vhs.data.OutputQuality.TAPE)
    )

    /**
     * A landscape-locked activity never gets onConfigurationChanged when the device is
     * turned, so this is the only thing that notices the window's rotation changing -
     * including the case that matters most: launching while the phone is held in
     * portrait, where the display settles into landscape a moment after the camera has
     * already bound.
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == currentDisplayId()) camera?.updateTargetRotation(windowRotation())
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        cameraPermitted = result[Manifest.permission.CAMERA] ?: cameraPermitted
        audioPermitted = result[Manifest.permission.RECORD_AUDIO] ?: audioPermitted
        if (cameraPermitted) {
            maybeStartCamera()
        } else {
            status = "CAMERA ACCESS DENIED"
        }
    }

    private val videoPicker = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) startProcessing(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goImmersive()

        settings = AppSettings(this)
        presetIndex = presets.indexOfFirst { it.id == settings.presetId }.coerceAtLeast(0)
        ui = SettingsUiState(
            osd = settings.osdEnabled,
            eraDate = settings.eraDate,
            vhsAudio = settings.vhsAudio,
            recordAudio = settings.recordAudio,
            letterbox = settings.letterbox,
            quality = settings.quality,
            rotationOffset = settings.rotationOffset
        )

        cameraPermitted = granted(Manifest.permission.CAMERA)
        audioPermitted = granted(Manifest.permission.RECORD_AUDIO)

        renderThread = VhsRenderThread(this).apply {
            preset = presets[presetIndex]
            osdEnabled = ui.osd
            eraDate = ui.eraDate
            start()
        }

        setContent {
            VhsTheme {
                CameraScreen(
                    presets = presets,
                    selectedIndex = presetIndex,
                    onSelectPreset = ::selectPreset,
                    recording = recording,
                    elapsedSec = elapsedSec,
                    onToggleRecord = ::toggleRecording,
                    onSwitchCamera = { camera?.switchCamera() },
                    onImport = {
                        videoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                        )
                    },
                    settings = ui,
                    onSettingsChange = ::applySettings,
                    processing = processing,
                    onCancelProcessing = { processor?.cancelled = true },
                    status = status,
                    onStatusShown = { status = null },
                    diagnostics = diagnostics,
                    onSaveDebugReport = ::saveDebugReport,
                    onRotatePicture = ::rotatePicture,
                    previewFactory = { context ->
                        VhsPreviewView(context).also { it.renderThread = renderThread }
                    }
                )
            }
        }

        requestMissingPermissions()
    }

    override fun onResume() {
        super.onResume()
        goImmersive()
        camera?.updateTargetRotation(windowRotation())
    }

    /** A viewfinder should not have a status bar in it. */
    private fun goImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onStart() {
        super.onStart()
        (getSystemService(DISPLAY_SERVICE) as DisplayManager)
            .registerDisplayListener(displayListener, main)
        camera?.updateTargetRotation(windowRotation())
    }

    override fun onStop() {
        super.onStop()
        (getSystemService(DISPLAY_SERVICE) as DisplayManager)
            .unregisterDisplayListener(displayListener)
    }

    override fun onPause() {
        super.onPause()
        // The camera stops feeding frames when we lose the foreground, so close the
        // take here rather than leaving a file that stalls wherever the user left it.
        if (recording) renderThread?.stopRecording()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        camera?.updateTargetRotation(windowRotation())
    }

    override fun onDestroy() {
        super.onDestroy()
        processor?.cancelled = true
        camera?.stop()
        renderThread?.shutdown()
        renderThread = null
        worker.shutdown()
    }

    // ---------------------------------------------------------------- user actions

    private fun selectPreset(index: Int) {
        if (index == presetIndex || recording) return
        presetIndex = index
        val preset = presets[index]
        settings.presetId = preset.id
        renderThread?.preset = preset
        renderThread?.triggerGlitch()
    }

    private fun applySettings(state: SettingsUiState) {
        val rotationChanged = state.rotationOffset != ui.rotationOffset
        ui = state
        settings.osdEnabled = state.osd
        settings.eraDate = state.eraDate
        settings.vhsAudio = state.vhsAudio
        settings.recordAudio = state.recordAudio
        settings.letterbox = state.letterbox
        settings.quality = state.quality
        settings.rotationOffset = state.rotationOffset
        renderThread?.osdEnabled = state.osd
        renderThread?.eraDate = state.eraDate
        if (rotationChanged) applyTransform()
        if (state.recordAudio && !audioPermitted) requestMissingPermissions()
    }

    private fun toggleRecording() {
        val thread = renderThread ?: return
        if (recording) {
            thread.stopRecording()
            return
        }
        if (!cameraPermitted) {
            requestMissingPermissions()
            return
        }
        try {
            val preset = presets[presetIndex]
            val output = MediaStoreSaver.createVideoOutput(
                this, MediaStoreSaver.fileName(preset.id)
            )
            pendingOutput = output
            val quality = ui.quality
            thread.startRecording(
                RecordRequest(
                    fileDescriptor = output.fileDescriptor,
                    path = output.path,
                    withAudio = ui.recordAudio && audioPermitted,
                    outputWidth = quality.width,
                    outputHeight = quality.height,
                    bitRate = quality.bitRate,
                    frameRate = FRAME_RATE
                )
            )
            recording = true
            elapsedSec = 0
        } catch (t: Throwable) {
            Log.e(TAG, "could not start recording", t)
            status = "CANNOT RECORD: ${t.message}"
        }
    }

    private fun startProcessing(uri: Uri) {
        if (processing != null) return
        val preset = presets[presetIndex]
        val quality = ui.quality
        val output = try {
            MediaStoreSaver.createVideoOutput(this, MediaStoreSaver.fileName("dub_${preset.id}"))
        } catch (t: Throwable) {
            status = "NO ROOM TO SAVE: ${t.message}"
            return
        }

        processing = ProcessingUiState(0f, "${preset.name.uppercase()} · ${preset.format}")
        val job = VideoFileProcessor(
            context = applicationContext,
            source = uri,
            preset = preset,
            osdEnabled = ui.osd,
            eraDate = ui.eraDate,
            vhsAudio = ui.vhsAudio,
            letterbox = ui.letterbox,
            outputWidth = quality.width,
            outputHeight = quality.height,
            bitRate = quality.bitRate
        )
        processor = job

        worker.execute {
            var ok = false
            var message: String? = null
            try {
                job.process(output) { progress ->
                    main.post { processing = processing?.copy(progress = progress) }
                }
                ok = true
            } catch (t: Throwable) {
                Log.e(TAG, "processing failed", t)
                message = if (job.cancelled) "STOPPED" else (t.message ?: "could not read that file")
            } finally {
                main.post {
                    MediaStoreSaver.finish(this, output, ok)
                    processing = null
                    processor = null
                    status = if (ok) "DUB SAVED TO MOVIES/${MediaStoreSaver.ALBUM}" else "DUB FAILED: $message"
                }
            }
        }
    }

    // ---------------------------------------------------------------- render callbacks

    override fun onSurfaceTextureReady(surfaceTexture: SurfaceTexture) {
        main.post {
            this.surfaceTexture = surfaceTexture
            maybeStartCamera()
        }
    }

    override fun onRecordingTick(seconds: Int) {
        main.post { elapsedSec = seconds }
    }

    override fun onRecordingFinished(success: Boolean, error: String?) {
        main.post {
            pendingOutput?.let { MediaStoreSaver.finish(this, it, success) }
            pendingOutput = null
            recording = false
            elapsedSec = 0
            status = when {
                success -> error ?: "TAPE SAVED TO MOVIES/${MediaStoreSaver.ALBUM}"
                else -> "RECORDING FAILED: ${error ?: "unknown"}"
            }
        }
    }

    override fun onRenderError(message: String) {
        main.post { status = "VIDEO SYSTEM ERROR: $message" }
    }

    // ---------------------------------------------------------------- plumbing

    private fun maybeStartCamera() {
        val texture = surfaceTexture ?: return
        if (!cameraPermitted || camera != null) return
        camera = CameraController(
            context = this,
            lifecycleOwner = this,
            onTransform = { transform ->
                lastTransform = transform
                applyTransform()
                diagnostics = buildString {
                    append(camera?.diagnostics ?: "")
                    append(" · window ")
                    append(windowRotation() * 90)
                    append("° · applied ")
                    append((transform.rotationDegrees + ui.rotationOffset) % 360)
                    append("°")
                }
            },
            onError = { message -> status = "CAMERA ERROR: $message" }
        ).also {
            it.start(texture)
            it.updateTargetRotation(windowRotation())
        }
    }

    /**
     * Collects everything needed to diagnose this device and writes it to Downloads,
     * then offers to share it. The GL facts have to come off the render thread, so the
     * report is assembled in that callback.
     */
    private fun saveDebugReport() {
        val thread = renderThread
        if (thread == null) {
            status = "RENDERER NOT RUNNING"
            return
        }
        thread.captureDiagnostics { renderInfo ->
            main.post {
                try {
                    val text = buildReport(renderInfo)
                    val saved = DebugReport.save(this, text)
                    status = "DEBUG REPORT: ${saved.path}"
                    shareReport(saved)
                } catch (t: Throwable) {
                    Log.e(TAG, "debug report failed", t)
                    status = "DEBUG REPORT FAILED: ${t.message}"
                }
            }
        }
    }

    private fun buildReport(renderInfo: String): String {
        val metrics = resources.displayMetrics
        val config = resources.configuration
        return buildString {
            appendLine("VHS-88 debug report")
            appendLine("generated        : ${java.util.Date()}")
            appendLine()
            appendLine("== device ==")
            appendLine("manufacturer     : ${Build.MANUFACTURER}")
            appendLine("model            : ${Build.MODEL} (${Build.DEVICE})")
            appendLine("android          : ${Build.VERSION.RELEASE} (api ${Build.VERSION.SDK_INT})")
            appendLine("build            : ${Build.DISPLAY}")
            appendLine()
            appendLine("== window and display ==")
            appendLine("display rotation : ${displayRotation() * 90}° (raw ${displayRotation()})")
            appendLine("window rotation  : ${windowRotation() * 90}° (what the camera is told)")
            appendLine("orientation      : ${if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"}")
            appendLine("screen           : ${metrics.widthPixels}x${metrics.heightPixels} @ ${metrics.densityDpi}dpi")
            appendLine("multi window     : ${isInMultiWindowMode}")
            appendLine()
            appendLine("== camera ==")
            append(camera?.hardwareReport() ?: "camera not started\n")
            appendLine()
            appendLine("== renderer ==")
            append(renderInfo)
            appendLine()
            appendLine("== settings ==")
            appendLine("rotation offset  : ${ui.rotationOffset}°")
            appendLine("output quality   : ${ui.quality.label}")
            appendLine("osd / era date   : ${ui.osd} / ${ui.eraDate}")
            appendLine("tape audio / mic : ${ui.vhsAudio} / ${ui.recordAudio}")
            appendLine("letterbox        : ${ui.letterbox}")
            appendLine()
            appendLine("If the picture is turned the wrong way, the number that matters is")
            appendLine("'applied rotation' against 'bound sensor rot' and 'window rotation'.")
        }
    }

    private fun shareReport(saved: DebugReport.Saved) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "VHS-88 debug report")
            if (saved.uri != null) {
                putExtra(Intent.EXTRA_STREAM, saved.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                putExtra(Intent.EXTRA_TEXT, saved.text)
            }
        }
        runCatching { startActivity(Intent.createChooser(intent, "Send debug report")) }
    }

    /**
     * Steps the picture a quarter turn. The phone is meant to be held horizontally, so
     * this is the direct control for when the automatic rotation guesses wrong on a
     * given device: it is on the main rail rather than buried in the menu, and it sticks.
     */
    private fun rotatePicture() {
        val next = (ui.rotationOffset + 90) % 360
        ui = ui.copy(rotationOffset = next)
        settings.rotationOffset = next
        applyTransform()
        status = if (next == 0) "PICTURE ROTATION: AUTO" else "PICTURE ROTATION: +$next°"
    }

    /** CameraX's automatic rotation, plus the user's manual correction if they set one. */
    private fun applyTransform() {
        val transform = lastTransform ?: return
        val corrected = if (ui.rotationOffset == 0) {
            transform
        } else {
            transform.copy(
                rotationDegrees = (transform.rotationDegrees + ui.rotationOffset) % 360
            )
        }
        renderThread?.setInputTransform(corrected)
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun requestMissingPermissions() {
        val wanted = mutableListOf<String>()
        if (!granted(Manifest.permission.CAMERA)) wanted += Manifest.permission.CAMERA
        if (!granted(Manifest.permission.RECORD_AUDIO)) wanted += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            !granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        ) {
            wanted += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        if (wanted.isNotEmpty()) {
            permissionLauncher.launch(wanted.toTypedArray())
        } else {
            maybeStartCamera()
        }
    }

    /**
     * The rotation the viewfinder is actually drawn at.
     *
     * The activity is locked to landscape, so the window is landscape no matter what the
     * display reports. Some devices keep reporting the natural portrait rotation for a
     * fixed-orientation activity; handing that to CameraX makes it deliver a picture
     * that is upright for a *portrait* window, which is 90 degrees out in ours. Clamping
     * to a landscape value keeps the camera aligned with the window we really have.
     */
    private fun windowRotation(): Int {
        val rotation = displayRotation()
        return if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            rotation
        } else {
            Surface.ROTATION_90
        }
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            windowManager.defaultDisplay.rotation
        }

    @Suppress("DEPRECATION")
    private fun currentDisplayId(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.displayId ?: Display.DEFAULT_DISPLAY
        } else {
            windowManager.defaultDisplay.displayId
        }

    private companion object {
        const val TAG = "MainActivity"
        const val FRAME_RATE = 30
    }
}
