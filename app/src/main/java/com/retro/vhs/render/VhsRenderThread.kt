package com.retro.vhs.render

import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.retro.vhs.gl.EglCore
import com.retro.vhs.gl.GlUtil
import com.retro.vhs.record.AudioRecorderThread
import com.retro.vhs.record.MuxerWrapper
import com.retro.vhs.record.VideoEncoderCore
import com.retro.vhs.vhs.InputTransform
import com.retro.vhs.vhs.OsdState
import com.retro.vhs.vhs.OsdStyle
import com.retro.vhs.vhs.VhsPipeline
import com.retro.vhs.vhs.VhsPreset
import java.io.FileDescriptor
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Everything the UI needs to hand over when the shutter is pressed. */
data class RecordRequest(
    val fileDescriptor: FileDescriptor?,
    val path: String?,
    val withAudio: Boolean,
    val outputWidth: Int,
    val outputHeight: Int,
    val bitRate: Int,
    val frameRate: Int
)

/**
 * The render thread. Owns the GL context, the camera's SurfaceTexture, the VHS pipeline
 * and the encoder, so a frame is filtered once and then presented to the screen and to
 * the encoder from the same result.
 */
class VhsRenderThread(private val callback: Callback) : HandlerThread(NAME),
    SurfaceTexture.OnFrameAvailableListener {

    interface Callback {
        fun onSurfaceTextureReady(surfaceTexture: SurfaceTexture)
        fun onRecordingTick(seconds: Int)
        fun onRecordingFinished(success: Boolean, error: String?)
        fun onRenderError(message: String)
    }

    private lateinit var handler: Handler

    private var egl: EglCore? = null
    private var offscreen: EGLSurface? = null
    private var windowSurface: EGLSurface? = null
    private var windowWidth = 0
    private var windowHeight = 0

    private var oesTexture = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var pipeline: VhsPipeline? = null

    private val stMatrix = FloatArray(16)
    private var transform = InputTransform()

    private var muxer: MuxerWrapper? = null
    private var videoEncoder: VideoEncoderCore? = null
    private var encoderSurface: EGLSurface? = null
    private var audioThread: AudioRecorderThread? = null
    private var recording = false
    private var recordBaseNs = 0L

    /**
     * Zero point for video timestamps, on the camera's clock. Taken from the first frame
     * that is actually encoded rather than from the wall clock, because a SurfaceTexture
     * timestamp and System.nanoTime() are not guaranteed to share an epoch - mixing them
     * stretches the recorded duration.
     */
    private var videoZeroNs = -1L
    private var lastTickSecond = -1
    private var recordedFrames = 0

    private var startNs = 0L
    private var counterBaseNs = 0L

    private var framesRendered = 0L
    private var fpsWindowStartNs = 0L
    private var fpsWindowFrames = 0
    private var measuredFps = 0f

    private var cachedSecond = -1L
    private var dateLine = ""
    private var timeLine = ""

    @Volatile
    var preset: VhsPreset = VhsPreset.ALL[0]
        set(value) {
            field = value
            // The pipeline belongs to the render thread; if it does not exist yet the
            // volatile field above is what onLooperPrepared reads.
            post { pipeline?.preset = value }
        }

    @Volatile
    var osdEnabled: Boolean = true

    @Volatile
    var eraDate: Boolean = true

    override fun onLooperPrepared() {
        handler = Handler(looper)
        try {
            val core = EglCore()
            egl = core
            offscreen = core.createOffscreenSurface(WORK_WIDTH, WORK_HEIGHT)
            core.makeCurrent(offscreen!!)

            oesTexture = GlUtil.createExternalTexture()
            val st = SurfaceTexture(oesTexture)
            st.setOnFrameAvailableListener(this, handler)
            surfaceTexture = st

            pipeline = VhsPipeline(WORK_WIDTH, WORK_HEIGHT).also { it.preset = preset }
            startNs = System.nanoTime()
            counterBaseNs = startNs

            callback.onSurfaceTextureReady(st)
        } catch (t: Throwable) {
            Log.e(TAG, "GL init failed", t)
            callback.onRenderError(t.message ?: "GL initialisation failed")
        }
    }

    // ---------------------------------------------------------------- public API

    fun post(action: () -> Unit) {
        if (::handler.isInitialized) handler.post(action)
    }

    fun surfaceAvailable(surface: Surface, width: Int, height: Int) = post {
        val core = egl ?: return@post
        releaseWindowSurface()
        windowSurface = core.createWindowSurface(surface)
        windowWidth = width
        windowHeight = height
    }

    fun surfaceChanged(width: Int, height: Int) = post {
        windowWidth = width
        windowHeight = height
    }

    fun surfaceDestroyed() = post { releaseWindowSurface() }

    fun setInputTransform(t: InputTransform) = post { transform = t }

    /** Called when the user changes tape, for the burst of mistracking that follows. */
    fun triggerGlitch() = post { pipeline?.glitch() }

    fun startRecording(request: RecordRequest) = post { handleStartRecording(request) }

    fun stopRecording() = post { handleStopRecording() }

    fun shutdown() {
        post {
            if (recording) handleStopRecording()
            releaseWindowSurface()
            pipeline?.release()
            pipeline = null
            surfaceTexture?.setOnFrameAvailableListener(null)
            surfaceTexture?.release()
            surfaceTexture = null
            GlUtil.deleteTexture(oesTexture)
            oesTexture = 0
            egl?.releaseSurface(offscreen)
            offscreen = null
            egl?.release()
            egl = null
            quitSafely()
        }
    }

    // ---------------------------------------------------------------- frame loop

    override fun onFrameAvailable(st: SurfaceTexture) {
        post { drawFrame() }
    }

    private fun drawFrame() {
        val core = egl ?: return
        val st = surfaceTexture ?: return
        val pipe = pipeline ?: return

        val drawSurface = windowSurface ?: offscreen ?: return
        try {
            core.makeCurrent(drawSurface)
            st.updateTexImage()
            st.getTransformMatrix(stMatrix)
        } catch (t: Throwable) {
            Log.w(TAG, "updateTexImage failed", t)
            return
        }

        val frameNs = st.timestamp.takeIf { it != 0L } ?: System.nanoTime()
        val timeSec = ((System.nanoTime() - startNs) % 1_000_000_000_000L) / 1_000_000_000f

        pipe.renderToTexture(oesTexture, stMatrix, transform, timeSec, buildOsdState())

        // 1. the viewfinder
        if (windowSurface != null && windowWidth > 0 && windowHeight > 0) {
            core.makeCurrent(windowSurface!!)
            pipe.drawToSurface(windowWidth, windowHeight, timeSec)
            core.swapBuffers(windowSurface!!)
        }

        // 2. the tape
        if (recording) {
            val encoder = videoEncoder
            val encSurface = encoderSurface
            if (encoder != null && encSurface != null) {
                if (videoZeroNs < 0L) videoZeroNs = frameNs
                val ptsNs = frameNs - videoZeroNs
                if (ptsNs >= 0) {
                    try {
                        encoder.drainEncoder(false)
                        core.makeCurrent(encSurface)
                        pipe.drawToSurface(
                            core.surfaceWidth(encSurface),
                            core.surfaceHeight(encSurface),
                            timeSec
                        )
                        core.setPresentationTime(encSurface, ptsNs)
                        core.swapBuffers(encSurface)
                        recordedFrames++
                    } catch (t: Throwable) {
                        Log.e(TAG, "encode failed", t)
                    }
                }
                val seconds = ((System.nanoTime() - recordBaseNs) / 1_000_000_000L).toInt()
                if (seconds != lastTickSecond) {
                    lastTickSecond = seconds
                    callback.onRecordingTick(seconds)
                }
            }
        }

        pipe.advanceFrame()

        framesRendered++
        fpsWindowFrames++
        val now = System.nanoTime()
        if (fpsWindowStartNs == 0L) {
            fpsWindowStartNs = now
        } else if (now - fpsWindowStartNs >= 1_000_000_000L) {
            measuredFps = fpsWindowFrames * 1_000_000_000f / (now - fpsWindowStartNs)
            fpsWindowStartNs = now
            fpsWindowFrames = 0
        }
    }

    /** Gathers everything only the GL thread can see, for the debug report. */
    fun captureDiagnostics(callback: (String) -> Unit) = post {
        val report = buildString {
            appendLine("gl vendor        : ${GLES20.glGetString(GLES20.GL_VENDOR)}")
            appendLine("gl renderer      : ${GLES20.glGetString(GLES20.GL_RENDERER)}")
            appendLine("gl version       : ${GLES20.glGetString(GLES20.GL_VERSION)}")
            appendLine("working raster   : ${WORK_WIDTH}x$WORK_HEIGHT")
            appendLine("preview surface  : ${windowWidth}x$windowHeight")
            appendLine("frames rendered  : $framesRendered")
            appendLine("measured fps     : ${"%.1f".format(measuredFps)}")
            appendLine("recording        : $recording")
            appendLine("applied rotation : ${transform.rotationDegrees}°")
            appendLine("applied mirror   : ${transform.mirror}")
            appendLine("source buffer    : ${transform.sourceWidth}x${transform.sourceHeight}")
            appendLine("letterbox fit    : ${transform.fit}")
            appendLine("preset           : ${preset.id} (${preset.name} ${preset.year})")
            appendLine("osd / era date   : $osdEnabled / $eraDate")
            appendLine("surfacetexture matrix (column major):")
            for (row in 0 until 4) {
                append("  ")
                for (col in 0 until 4) {
                    append("%8.3f".format(stMatrix[col * 4 + row]))
                }
                appendLine()
            }
        }
        callback(report)
    }

    private fun buildOsdState(): OsdState {
        val nowMs = System.currentTimeMillis()
        val second = nowMs / 1000L
        if (second != cachedSecond) {
            cachedSecond = second
            val cal = Calendar.getInstance()
            if (eraDate) {
                preset.year.toIntOrNull()?.let { cal.set(Calendar.YEAR, it) }
            }
            dateLine = DATE_FORMAT.format(cal.time).uppercase(Locale.US)
            timeLine = TIME_FORMAT.format(cal.time).uppercase(Locale.US)
        }
        val elapsed = if (recording) {
            ((System.nanoTime() - recordBaseNs) / 1_000_000_000L).toInt()
        } else {
            ((System.nanoTime() - counterBaseNs) / 1_000_000_000L).toInt()
        }
        return OsdState(
            style = if (osdEnabled) preset.osd else OsdStyle.NONE,
            color = preset.osdColor,
            recording = recording,
            elapsedSec = elapsed,
            dateLine = dateLine,
            timeLine = timeLine,
            speed = preset.tapeSpeed,
            blink = (System.currentTimeMillis() / 600L) % 2L == 0L,
            battery = 0.75f
        )
    }

    // ---------------------------------------------------------------- recording

    private fun handleStartRecording(request: RecordRequest) {
        if (recording) return
        val core = egl ?: return
        try {
            val wrapper = when {
                request.fileDescriptor != null ->
                    MuxerWrapper(request.fileDescriptor, if (request.withAudio) 2 else 1)

                request.path != null ->
                    MuxerWrapper(request.path, if (request.withAudio) 2 else 1)

                else -> throw IllegalArgumentException("no output for recording")
            }
            wrapper.baseTimeNs = System.nanoTime()
            recordBaseNs = wrapper.baseTimeNs

            val encoder = VideoEncoderCore(
                request.outputWidth,
                request.outputHeight,
                request.bitRate,
                request.frameRate,
                wrapper
            )
            encoderSurface = core.createWindowSurface(encoder.inputSurface)

            muxer = wrapper
            videoEncoder = encoder
            recordedFrames = 0
            lastTickSecond = -1
            videoZeroNs = -1L

            if (request.withAudio) {
                audioThread = AudioRecorderThread(preset.audio, wrapper).also { it.start() }
            }
            recording = true
            callback.onRecordingTick(0)
        } catch (t: Throwable) {
            Log.e(TAG, "startRecording failed", t)
            cleanupRecording()
            callback.onRecordingFinished(false, t.message ?: "could not start recording")
        }
    }

    private fun handleStopRecording() {
        if (!recording) return
        recording = false
        var error: String? = null
        try {
            egl?.let { core -> (windowSurface ?: offscreen)?.let { core.makeCurrent(it) } }
            videoEncoder?.drainEncoder(true)
        } catch (t: Throwable) {
            Log.e(TAG, "final drain failed", t)
            error = t.message
        }
        audioThread?.stopRecording()
        val soundless = audioThread?.failed == true
        val frames = recordedFrames
        cleanupRecording()
        val ok = error == null && frames > 0
        val note = when {
            error != null -> error
            frames == 0 -> "no frames captured"
            soundless -> "TAPE SAVED · NO SOUND ON THIS TAKE"
            else -> null
        }
        callback.onRecordingFinished(ok, note)
    }

    private fun cleanupRecording() {
        audioThread = null
        egl?.releaseSurface(encoderSurface)
        encoderSurface = null
        videoEncoder?.release()
        videoEncoder = null
        muxer?.stop()
        muxer = null
        counterBaseNs = System.nanoTime()
    }

    private fun releaseWindowSurface() {
        windowSurface?.let { egl?.releaseSurface(it) }
        windowSurface = null
        windowWidth = 0
        windowHeight = 0
    }

    companion object {
        private const val TAG = "VhsRenderThread"
        private const val NAME = "VhsRender"

        /** The tape stage always runs at 480 lines - the artefacts are tuned in pixels. */
        const val WORK_WIDTH = 640
        const val WORK_HEIGHT = 480

        private val DATE_FORMAT = SimpleDateFormat("MMM d yyyy", Locale.US)
        private val TIME_FORMAT = SimpleDateFormat("h:mm:ss a", Locale.US)
    }
}
