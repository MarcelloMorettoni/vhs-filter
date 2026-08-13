package com.retro.vhs.record

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import com.retro.vhs.vhs.AudioProfile
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * Microphone -> [VhsAudioProcessor] -> AAC -> muxer, on its own thread.
 * Timestamps come from the sample count so the track never drifts against the video.
 */
class AudioRecorderThread(
    private val profile: AudioProfile,
    private val muxer: MuxerWrapper
) : Thread("vhs-audio") {

    @Volatile
    private var running = true

    @Volatile
    var failed = false
        private set

    private val channels = if (profile.mono) 1 else 2
    private val bufferInfo = MediaCodec.BufferInfo()
    private var trackIndex = -1

    fun stopRecording() {
        running = false
        try {
            join(2500)
        } catch (e: InterruptedException) {
            Log.w(TAG, "interrupted while stopping audio", e)
        }
    }

    @SuppressLint("MissingPermission")
    override fun run() {
        var record: AudioRecord? = null
        var codec: MediaCodec? = null
        try {
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            check(minBuffer > 0) { "AudioRecord unavailable" }
            val bufferBytes = max(minBuffer * 2, CHUNK_FRAMES * 8)

            record = AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes
            ).takeIf { it.state == AudioRecord.STATE_INITIALIZED } ?: AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes
            )
            check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord init failed" }

            val format = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, channels).apply {
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC
                )
                setInteger(MediaFormat.KEY_BIT_RATE, if (channels == 1) 96_000 else 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK_FRAMES * 8)
            }
            codec = MediaCodec.createEncoderByType(MIME_TYPE)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val dsp = VhsAudioProcessor(profile, SAMPLE_RATE)
            val pcm = ShortArray(CHUNK_FRAMES)
            val out = ShortArray(CHUNK_FRAMES * channels)

            record.startRecording()

            var totalFrames = 0L
            var offsetUs = -1L

            while (running) {
                val read = record.read(pcm, 0, CHUNK_FRAMES)
                if (read <= 0) {
                    // A stub or stalled input device would otherwise spin this thread.
                    Thread.sleep(5)
                    continue
                }

                val base = muxer.baseTimeNs
                val chunkUs = read * 1_000_000L / SAMPLE_RATE
                val arrivedUs = if (base == 0L) 0L else max(0L, (System.nanoTime() - base) / 1000L)
                val bufferStartUs = max(0L, arrivedUs - chunkUs)
                if (offsetUs < 0) offsetUs = bufferStartUs

                // Timestamps come from the sample count, which is what keeps playback
                // smooth. If the input device ever falls behind real time, step the
                // offset forward instead of letting the track drift out of sync
                // with the picture for the rest of the take.
                var ptsUs = offsetUs + totalFrames * 1_000_000L / SAMPLE_RATE
                if (bufferStartUs - ptsUs > RESYNC_US) {
                    offsetUs += bufferStartUs - ptsUs
                    ptsUs = bufferStartUs
                }

                dsp.process(pcm, read)
                val outCount = dsp.toOutput(pcm, read, out)

                totalFrames += read
                feed(codec, out, outCount, ptsUs, false)
                drain(codec, false)
            }

            val endUs = offsetUs.coerceAtLeast(0L) + totalFrames * 1_000_000L / SAMPLE_RATE
            feed(codec, out, 0, endUs, true)
            drain(codec, true)
            Log.d(
                TAG,
                "captured ${totalFrames * 1000L / SAMPLE_RATE} ms of audio in " +
                    "${(System.nanoTime() - muxer.baseTimeNs) / 1_000_000L} ms of wall clock"
            )
        } catch (e: Exception) {
            failed = true
            Log.e(TAG, "audio recording failed", e)
            // Let the muxer go ahead with video only rather than losing the take.
            if (trackIndex < 0) muxer.abandonTrack()
        } finally {
            try {
                record?.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "AudioRecord stop failed", e)
            }
            record?.release()
            try {
                codec?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "audio codec stop failed", e)
            }
            codec?.release()
        }
    }

    private fun feed(
        codec: MediaCodec,
        data: ShortArray,
        count: Int,
        ptsUs: Long,
        endOfStream: Boolean
    ) {
        var offset = 0
        var attempts = 0
        while (true) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) {
                if (++attempts > MAX_FEED_ATTEMPTS) return
                drain(codec, false)
                continue
            }
            attempts = 0
            val buffer = codec.getInputBuffer(index) ?: return
            buffer.clear()
            val room = buffer.remaining() / 2
            val n = min(room, count - offset)
            if (n > 0) {
                buffer.order(ByteOrder.nativeOrder()).asShortBuffer().put(data, offset, n)
            }
            val last = offset + n >= count
            val flags = if (endOfStream && last) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
            val framePtsUs = ptsUs + offset.toLong() * 1_000_000L / (SAMPLE_RATE * channels)
            codec.queueInputBuffer(index, 0, n * 2, framePtsUs, flags)
            offset += n
            if (last) return
        }
    }

    private fun drain(codec: MediaCodec, endOfStream: Boolean) {
        while (true) {
            val status = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                status == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return

                status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (trackIndex < 0) trackIndex = muxer.addTrack(codec.outputFormat)
                }

                status < 0 -> Log.w(TAG, "unexpected audio dequeue status $status")

                else -> {
                    val data = codec.getOutputBuffer(status)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }
                    if (data != null && bufferInfo.size > 0 && trackIndex >= 0) {
                        data.position(bufferInfo.offset)
                        data.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, data, bufferInfo)
                    }
                    codec.releaseOutputBuffer(status, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    companion object {
        private const val TAG = "AudioRecorder"
        private const val MIME_TYPE = "audio/mp4a-latm"
        const val SAMPLE_RATE = 44_100
        private const val CHUNK_FRAMES = 1024
        private const val TIMEOUT_US = 10_000L

        /** Slack before the capture clock is pulled back onto the wall clock. */
        private const val RESYNC_US = 250_000L

        /** ~2 seconds of retrying before giving up on a wedged encoder. */
        private const val MAX_FEED_ATTEMPTS = 200
    }
}
