package com.retro.vhs.media

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.retro.vhs.gl.EglCore
import com.retro.vhs.gl.GlUtil
import com.retro.vhs.record.MuxerWrapper
import com.retro.vhs.record.VideoEncoderCore
import com.retro.vhs.vhs.AudioProfile
import com.retro.vhs.vhs.InputTransform
import com.retro.vhs.vhs.OsdState
import com.retro.vhs.vhs.OsdStyle
import com.retro.vhs.vhs.VhsPipeline
import com.retro.vhs.vhs.VhsPreset
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Runs an existing video file through the same pipeline the camera uses: decode to a
 * SurfaceTexture, filter, re-encode. The audio is transcoded in parallel so the finished
 * file has both tracks.
 */
class VideoFileProcessor(
    private val context: Context,
    private val source: Uri,
    private val preset: VhsPreset,
    private val osdEnabled: Boolean,
    private val eraDate: Boolean,
    private val vhsAudio: Boolean,
    private val letterbox: Boolean,
    private val outputWidth: Int,
    private val outputHeight: Int,
    private val bitRate: Int
) {

    @Volatile
    var cancelled = false

    private val frameReady = Semaphore(0)

    fun process(output: MediaStoreSaver.Output, onProgress: (Float) -> Unit) {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoderCore: VideoEncoderCore? = null
        var muxer: MuxerWrapper? = null
        var egl: EglCore? = null
        var encoderSurface: EGLSurface? = null
        var surfaceTexture: SurfaceTexture? = null
        var decoderSurface: Surface? = null
        var pipeline: VhsPipeline? = null
        var oesTexture = 0
        var audio: AudioFileTranscoder? = null
        val callbackThread = HandlerThread("vhs-file-frames").apply { start() }

        try {
            extractor = MediaExtractor().apply { setDataSource(context, source, null) }
            val videoTrack = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            } ?: throw IllegalArgumentException("that file has no video track")

            extractor.selectTrack(videoTrack)
            val inputFormat = extractor.getTrackFormat(videoTrack)
            val srcWidth = inputFormat.getInteger(MediaFormat.KEY_WIDTH)
            val srcHeight = inputFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val rotation = if (inputFormat.containsKey(KEY_ROTATION)) {
                inputFormat.getInteger(KEY_ROTATION)
            } else {
                0
            }
            val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                inputFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }

            val hasAudio = (0 until extractor.trackCount).any {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            }

            muxer = when {
                output.fileDescriptor != null ->
                    MuxerWrapper(output.fileDescriptor!!, if (hasAudio) 2 else 1)

                output.path != null -> MuxerWrapper(output.path, if (hasAudio) 2 else 1)
                else -> throw IllegalStateException("no output location")
            }

            egl = EglCore()
            encoderCore = VideoEncoderCore(
                outputWidth, outputHeight, bitRate, FRAME_RATE, muxer
            )
            encoderSurface = egl.createWindowSurface(encoderCore.inputSurface)
            egl.makeCurrent(encoderSurface)

            oesTexture = GlUtil.createExternalTexture()
            surfaceTexture = SurfaceTexture(oesTexture)
            surfaceTexture.setDefaultBufferSize(srcWidth, srcHeight)
            surfaceTexture.setOnFrameAvailableListener(
                { frameReady.release() }, Handler(callbackThread.looper)
            )
            decoderSurface = Surface(surfaceTexture)

            pipeline = VhsPipeline(WORK_WIDTH, WORK_HEIGHT).also { it.preset = preset }
            val transform = InputTransform(
                rotationDegrees = ((rotation % 360) + 360) % 360,
                mirror = false,
                sourceWidth = srcWidth,
                sourceHeight = srcHeight,
                fit = letterbox
            )

            if (hasAudio) {
                val profile = if (vhsAudio) preset.audio else AudioProfile.CLEAN
                audio = AudioFileTranscoder(context, source, profile, muxer).also { it.start() }
            }

            decoder = MediaCodec.createDecoderByType(
                inputFormat.getString(MediaFormat.KEY_MIME)!!
            )
            decoder.configure(inputFormat, decoderSurface, null, 0)
            decoder.start()

            val info = MediaCodec.BufferInfo()
            val stMatrix = FloatArray(16)
            var inputDone = false
            var outputDone = false
            var frames = 0

            while (!outputDone && !cancelled) {
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val status = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                if (status >= 0) {
                    val render = info.size > 0
                    val ptsUs = info.presentationTimeUs
                    decoder.releaseOutputBuffer(status, render)

                    if (render) {
                        if (!frameReady.tryAcquire(FRAME_WAIT_MS, TimeUnit.MILLISECONDS)) {
                            throw IllegalStateException("timed out waiting for a decoded frame")
                        }
                        surfaceTexture.updateTexImage()
                        surfaceTexture.getTransformMatrix(stMatrix)

                        val timeSec = ptsUs / 1_000_000f
                        pipeline.renderToTexture(
                            oesTexture, stMatrix, transform, timeSec, osdState(ptsUs)
                        )
                        encoderCore.drainEncoder(false)
                        egl.makeCurrent(encoderSurface)
                        pipeline.drawToSurface(outputWidth, outputHeight, timeSec)
                        egl.setPresentationTime(encoderSurface, ptsUs * 1000L)
                        // ...and again out of band, so the written timestamps come from
                        // the source rather than from how fast this device is running.
                        encoderCore.queueSourceTimestamp(ptsUs)
                        egl.swapBuffers(encoderSurface)
                        pipeline.advanceFrame()
                        frames++

                        if (durationUs > 0) {
                            onProgress((ptsUs.toFloat() / durationUs).coerceIn(0f, 1f))
                        }
                    }

                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }

            if (cancelled) throw InterruptedException("cancelled")
            check(frames > 0) { "no frames could be decoded from that file" }

            encoderCore.drainEncoder(true)
            audio?.join(15_000)
            onProgress(1f)
        } finally {
            audio?.cancelled = true
            try {
                decoder?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "decoder stop failed", e)
            }
            decoder?.release()
            extractor?.release()
            pipeline?.release()
            encoderCore?.release()
            decoderSurface?.release()
            surfaceTexture?.setOnFrameAvailableListener(null)
            surfaceTexture?.release()
            GlUtil.deleteTexture(oesTexture)
            egl?.releaseSurface(encoderSurface)
            egl?.release()
            muxer?.stop()
            callbackThread.quitSafely()
        }
    }

    private fun osdState(ptsUs: Long): OsdState {
        val cal = Calendar.getInstance()
        if (eraDate) preset.year.toIntOrNull()?.let { cal.set(Calendar.YEAR, it) }
        val seconds = (ptsUs / 1_000_000L).toInt()
        return OsdState(
            style = if (osdEnabled) preset.osd else OsdStyle.NONE,
            color = preset.osdColor,
            recording = false,
            elapsedSec = seconds,
            dateLine = DATE_FORMAT.format(cal.time).uppercase(Locale.US),
            timeLine = TIME_FORMAT.format(cal.time).uppercase(Locale.US),
            speed = preset.tapeSpeed,
            blink = (ptsUs / 600_000L) % 2L == 0L,
            battery = 0.75f
        )
    }

    companion object {
        private const val TAG = "VideoFileProcessor"
        private const val KEY_ROTATION = "rotation-degrees"
        private const val TIMEOUT_US = 10_000L
        private const val FRAME_WAIT_MS = 4_000L
        private const val FRAME_RATE = 30
        const val WORK_WIDTH = 640
        const val WORK_HEIGHT = 480

        private val DATE_FORMAT = SimpleDateFormat("MMM d yyyy", Locale.US)
        private val TIME_FORMAT = SimpleDateFormat("h:mm:ss a", Locale.US)
    }
}
