package com.retro.vhs.record

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * MediaMuxer is not thread safe and refuses samples before every track is registered,
 * so the video thread and the audio thread both go through here.
 */
class MuxerWrapper private constructor(
    private val muxer: MediaMuxer,
    private var expectedTracks: Int
) {

    constructor(fd: FileDescriptor, expectedTracks: Int) : this(
        MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4), expectedTracks
    )

    constructor(path: String, expectedTracks: Int) : this(
        MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4), expectedTracks
    )

    private var addedTracks = 0
    private var started = false
    private var stopped = false

    /**
     * MediaMuxer will not take a sample until every track has been registered, and the
     * audio encoder cannot register until the microphone has produced its first buffer.
     * Rather than throw away the opening second of the take, hold the samples here and
     * flush them the moment the muxer opens.
     */
    private val pending = ArrayDeque<PendingSample>()
    private var pendingBytes = 0

    private class PendingSample(
        val track: Int,
        val data: ByteBuffer,
        val info: MediaCodec.BufferInfo
    )

    /** Shared zero point so audio and video agree on t=0. */
    @Volatile
    var baseTimeNs: Long = 0L

    @Synchronized
    fun addTrack(format: MediaFormat): Int {
        check(!started) { "muxer already started" }
        val index = muxer.addTrack(format)
        addedTracks++
        if (addedTracks == expectedTracks) {
            muxer.start()
            started = true
            flushPending()
        }
        return index
    }

    /**
     * A source that will never deliver (no microphone permission, an audio codec we
     * cannot decode). Without this the muxer would wait for a track that never arrives
     * and the video would be thrown away with it.
     */
    @Synchronized
    fun abandonTrack() {
        if (started) return
        expectedTracks--
        if (addedTracks > 0 && addedTracks >= expectedTracks) {
            muxer.start()
            started = true
            flushPending()
        }
    }

    @Synchronized
    fun writeSampleData(track: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (stopped || info.size <= 0) return
        if (!started) {
            hold(track, buffer, info)
            return
        }
        try {
            muxer.writeSampleData(track, buffer, info)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "writeSampleData failed", e)
        }
    }

    private fun hold(track: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        // Oldest first, so if the cap is hit we lose the very start rather than the
        // frames closest to the point where the file becomes writable.
        while (pendingBytes + info.size > MAX_PENDING_BYTES && pending.isNotEmpty()) {
            pendingBytes -= pending.removeFirst().info.size
        }
        if (info.size > MAX_PENDING_BYTES) return

        val copy = ByteBuffer.allocateDirect(info.size)
        val source = buffer.duplicate()
        source.position(info.offset)
        source.limit(info.offset + info.size)
        copy.put(source)
        copy.flip()

        val held = MediaCodec.BufferInfo().apply {
            set(0, info.size, info.presentationTimeUs, info.flags)
        }
        pending.addLast(PendingSample(track, copy, held))
        pendingBytes += info.size
    }

    private fun flushPending() {
        while (pending.isNotEmpty()) {
            val sample = pending.removeFirst()
            try {
                muxer.writeSampleData(sample.track, sample.data, sample.info)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "flushing a held sample failed", e)
            }
        }
        pendingBytes = 0
    }

    @Synchronized
    fun stop() {
        if (stopped) return
        stopped = true
        try {
            if (started) muxer.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "muxer stop failed", e)
        }
        try {
            muxer.release()
        } catch (e: Exception) {
            Log.w(TAG, "muxer release failed", e)
        }
    }

    companion object {
        private const val TAG = "MuxerWrapper"

        /** Roughly three seconds of 640x480 video; enough for any microphone to wake up. */
        private const val MAX_PENDING_BYTES = 12 * 1024 * 1024
    }
}
