package com.retro.vhs.record

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface

/**
 * H.264 encoder fed by an OpenGL surface: the renderer draws the finished VHS frame
 * straight into [inputSurface], so what is recorded is bit for bit what was previewed.
 */
class VideoEncoderCore(
    width: Int,
    height: Int,
    bitRate: Int,
    frameRate: Int,
    private val muxer: MuxerWrapper
) {

    private val bufferInfo = MediaCodec.BufferInfo()
    private val encoder: MediaCodec
    val inputSurface: Surface
    private var trackIndex = -1

    /**
     * Timestamps to stamp the encoded frames with, in the order they were drawn.
     *
     * Surface input is supposed to carry the time set by eglPresentationTimeANDROID,
     * but some encoders stamp by when the frame arrived instead. That is harmless while
     * frames are drawn at real time, and turns the file into slow motion the moment the
     * pipeline falls behind - which is exactly what happens when a device throttles part
     * way through a long transcode. Feeding the source timestamps through here makes the
     * output depend on the material rather than on how fast the phone happened to run.
     *
     * Only used by the file transcoder; live capture leaves it empty and keeps whatever
     * the encoder reports, which is already the camera's own clock.
     */
    private val sourceTimestamps = ArrayDeque<Long>()

    fun queueSourceTimestamp(presentationTimeUs: Long) {
        sourceTimestamps.addLast(presentationTimeUs)
    }

    init {
        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        encoder = MediaCodec.createEncoderByType(MIME_TYPE)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = encoder.createInputSurface()
        encoder.start()
    }

    fun drainEncoder(endOfStream: Boolean) {
        if (endOfStream) {
            try {
                encoder.signalEndOfInputStream()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "signalEndOfInputStream failed", e)
            }
        }

        while (true) {
            val status = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                status == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                }

                status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(trackIndex < 0) { "format changed twice" }
                    trackIndex = muxer.addTrack(encoder.outputFormat)
                }

                status < 0 -> Log.w(TAG, "unexpected dequeueOutputBuffer status $status")

                else -> {
                    val data = encoder.getOutputBuffer(status)
                        ?: throw RuntimeException("encoder output buffer $status was null")
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size != 0 && trackIndex >= 0) {
                        if (sourceTimestamps.isNotEmpty()) {
                            bufferInfo.presentationTimeUs = sourceTimestamps.removeFirst()
                        }
                        data.position(bufferInfo.offset)
                        data.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, data, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(status, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    fun release() {
        try {
            encoder.stop()
        } catch (e: Exception) {
            Log.w(TAG, "encoder stop failed", e)
        }
        encoder.release()
        inputSurface.release()
    }

    companion object {
        private const val TAG = "VideoEncoderCore"
        private const val MIME_TYPE = "video/avc"
        private const val TIMEOUT_US = 10_000L
    }
}
