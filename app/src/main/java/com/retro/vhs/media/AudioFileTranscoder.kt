package com.retro.vhs.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.retro.vhs.record.MuxerWrapper
import com.retro.vhs.record.VhsAudioProcessor
import com.retro.vhs.vhs.AudioProfile
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Decodes the source file's audio, drags it through the tape's audio path and re-encodes
 * it as AAC. Runs alongside the video pass so both tracks reach the muxer.
 */
class AudioFileTranscoder(
    private val context: Context,
    private val source: Uri,
    private val profile: AudioProfile,
    private val muxer: MuxerWrapper
) : Thread("vhs-audio-transcode") {

    @Volatile
    var cancelled = false

    private val bufferInfo = MediaCodec.BufferInfo()
    private var trackIndex = -1

    override fun run() {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        try {
            extractor = MediaExtractor().apply { setDataSource(context, source, null) }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: run {
                muxer.abandonTrack()
                return
            }

            extractor.selectTrack(track)
            val inputFormat = extractor.getTrackFormat(track)
            val sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val sourceChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val outChannels = if (profile.mono) 1 else 2

            decoder = MediaCodec.createDecoderByType(
                inputFormat.getString(MediaFormat.KEY_MIME)!!
            )
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val outFormat = MediaFormat.createAudioFormat(AAC, sampleRate, outChannels).apply {
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC
                )
                setInteger(MediaFormat.KEY_BIT_RATE, if (outChannels == 1) 96_000 else 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32 * 1024)
            }
            encoder = MediaCodec.createEncoderByType(AAC)
            encoder.configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val dsp = VhsAudioProcessor(profile, sampleRate)
            var mono = ShortArray(4096)
            var out = ShortArray(8192)
            var totalFrames = 0L

            var inputDone = false
            var decodeDone = false
            val decodeInfo = MediaCodec.BufferInfo()

            while (!decodeDone && !cancelled) {
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

                val status = decoder.dequeueOutputBuffer(decodeInfo, TIMEOUT_US)
                if (status >= 0) {
                    val buffer = decoder.getOutputBuffer(status)
                    if (decodeInfo.size > 0 && buffer != null) {
                        buffer.position(decodeInfo.offset)
                        buffer.limit(decodeInfo.offset + decodeInfo.size)
                        val shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val total = shorts.remaining()
                        val frames = total / sourceChannels
                        if (mono.size < frames) mono = ShortArray(frames)
                        if (out.size < frames * outChannels) out = ShortArray(frames * outChannels)

                        // Everything the tape did was mono-in-mono-out; downmix first.
                        var s = 0
                        for (f in 0 until frames) {
                            var acc = 0
                            for (c in 0 until sourceChannels) acc += shorts.get(s++).toInt()
                            mono[f] = (acc / sourceChannels).toShort()
                        }

                        dsp.process(mono, frames)
                        val outCount = dsp.toOutput(mono, frames, out)
                        val ptsUs = totalFrames * 1_000_000L / sampleRate
                        totalFrames += frames
                        feed(encoder, out, outCount, ptsUs, false, sampleRate, outChannels)
                        drain(encoder, false)
                    }
                    decoder.releaseOutputBuffer(status, false)
                    if (decodeInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        decodeDone = true
                    }
                }
            }

            val endUs = totalFrames * 1_000_000L / sampleRate
            feed(encoder, out, 0, endUs, true, sampleRate, outChannels)
            drain(encoder, true)
        } catch (t: Throwable) {
            Log.e(TAG, "audio transcode failed", t)
            if (trackIndex < 0) muxer.abandonTrack()
        } finally {
            try {
                decoder?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "decoder stop failed", e)
            }
            decoder?.release()
            try {
                encoder?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "encoder stop failed", e)
            }
            encoder?.release()
            extractor?.release()
        }
    }

    private fun feed(
        codec: MediaCodec,
        data: ShortArray,
        count: Int,
        ptsUs: Long,
        endOfStream: Boolean,
        sampleRate: Int,
        channels: Int
    ) {
        var offset = 0
        var attempts = 0
        while (true) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) {
                // Transcoding runs far faster than real time, so the encoder's input
                // queue fills constantly. Make room and retry - dropping the chunk here
                // is what truncates the finished track.
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
            val pts = ptsUs + offset.toLong() * 1_000_000L / (sampleRate * channels)
            codec.queueInputBuffer(index, 0, n * 2, pts, flags)
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

                status < 0 -> Log.w(TAG, "unexpected status $status")

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
        private const val TAG = "AudioTranscode"
        private const val AAC = "audio/mp4a-latm"
        private const val TIMEOUT_US = 10_000L

        /** ~2 seconds of retrying before giving up on a wedged encoder. */
        private const val MAX_FEED_ATTEMPTS = 200
    }
}
