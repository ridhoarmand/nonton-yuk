package com.nonton.yuks

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer

/** Verification for existing MP4s and recovery of pre-cache completed tracks. */
object MediaTransfer {
    private const val MAX_SAMPLE_BYTES = 16 * 1024 * 1024

    fun verify(file: File, requireAudio: Boolean): Boolean =
        completeTrack(file, MediaFormat.MIMETYPE_VIDEO_AVC) &&
            (!requireAudio || completeTrack(file, MediaFormat.MIMETYPE_AUDIO_AAC))

    fun completeTrack(file: File, mime: String): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == mime
            } ?: return false
            val format = extractor.getTrackFormat(track)
            if (!format.containsKey(MediaFormat.KEY_DURATION)) return false
            val duration = format.getLong(MediaFormat.KEY_DURATION)
            if (duration <= 0L) return false
            extractor.selectTrack(track)
            var buffer = ByteBuffer.allocateDirect(512 * 1024)
            fun readableSample(): Boolean {
                val size = extractor.sampleSize
                if (size <= 0L || size > MAX_SAMPLE_BYTES) return false
                if (buffer.capacity() < size) buffer = ByteBuffer.allocateDirect(size.toInt())
                buffer.clear()
                return extractor.readSampleData(buffer, 0) == size.toInt()
            }
            if (!readableSample()) return false
            // Checking the final GOP catches truncated old partials without re-reading the movie.
            extractor.seekTo((duration - 1L).coerceAtLeast(0L), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            var lastTime = -1L
            do {
                if (!readableSample()) return false
                lastTime = extractor.sampleTime
            } while (extractor.advance())
            lastTime >= (duration - 1_000_000L).coerceAtLeast(0L)
        } catch (_: Exception) {
            false
        } finally {
            runCatching { extractor.release() }
        }
    }
}
