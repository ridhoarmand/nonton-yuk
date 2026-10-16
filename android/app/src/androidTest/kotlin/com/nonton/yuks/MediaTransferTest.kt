package com.nonton.yuks

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

private const val VIDEO = MediaFormat.MIMETYPE_VIDEO_AVC
private const val AUDIO = MediaFormat.MIMETYPE_AUDIO_AAC
private const val TIMING_TOLERANCE_US = 100_000L

/** One track as an extractor sees it: sample count, duration and the presentation times in order. */
private class TrackStat(val samples: Int, val durationUs: Long, val pts: List<Long>)

/** Sample-copy muxing on real device decoders; the fixtures are the assets in src/androidTest/assets. */
@RunWith(AndroidJUnit4::class)
class MediaTransferTest {
    private fun asset(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = File(instrumentation.targetContext.cacheDir, name)
        val parent = target.parentFile
        check(parent == null || parent.exists() || parent.mkdirs()) { "cache ${parent?.absolutePath} tidak dapat dibuat" }
        instrumentation.context.assets.open(name).use { input ->
            FileOutputStream(target).use { input.copyTo(it) }
        }
        return target
    }

    private fun output(name: String): File =
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, name).also { it.delete() }

    @Test
    fun muxCopiesBothTracksAndKeepsSourceTiming() {
        val source = asset("video.mp4")
        val soundtrack = asset("audio.m4a")
        val muxed = output("muxed.mp4")

        MediaTransfer.mux(source, soundtrack, muxed) { false }

        assertTrue("hasil gabungan harus lolos verifikasi", MediaTransfer.verify(muxed, requireAudio = true))
        assertEquals(2, trackCount(muxed))
        val video = stats(source, VIDEO)
        val audio = stats(soundtrack, AUDIO)
        val mergedVideo = stats(muxed, VIDEO)
        val mergedAudio = stats(muxed, AUDIO)
        assertTrue(video.samples > 0 && audio.samples > 0)
        // Copying samples verbatim: same count, same sample order, same presentation times.
        assertEquals(video.samples, mergedVideo.samples)
        assertEquals(video.pts, mergedVideo.pts)
        assertEquals(audio.samples, mergedAudio.samples)
        assertEquals(audio.pts, mergedAudio.pts)
        assertWithinTolerance(video.durationUs, mergedVideo.durationUs)
        assertWithinTolerance(audio.durationUs, mergedAudio.durationUs)
    }

    @Test
    fun muxInterleavesUnequalTrackLengths() {
        val muxed = output("muxed-short-audio.mp4")
        MediaTransfer.mux(asset("video.mp4"), asset("audio-short.m4a"), muxed) { false }

        assertTrue(MediaTransfer.verify(muxed, requireAudio = true))
        assertEquals(stats(asset("video.mp4"), VIDEO).samples, stats(muxed, VIDEO).samples)
        assertEquals(stats(asset("audio-short.m4a"), AUDIO).samples, stats(muxed, AUDIO).samples)
        assertWithinTolerance(stats(asset("audio-short.m4a"), AUDIO).durationUs, stats(muxed, AUDIO).durationUs)
    }

    @Test
    fun verifyRejectsFilesWithoutThePromisedTracks() {
        assertTrue(MediaTransfer.verify(asset("video.mp4"), requireAudio = false))
        assertFalse(MediaTransfer.verify(asset("video.mp4"), requireAudio = true))
        assertFalse(MediaTransfer.verify(asset("audio.m4a"), requireAudio = false))
        assertFalse(MediaTransfer.verify(asset("audio.m4a"), requireAudio = true))
    }

    @Test
    fun truncatedInputNeverBecomesAPlayableFile() {
        val broken = output("broken.mp4")
        assertFalse(MediaTransfer.verify(asset("video-truncated.mp4"), requireAudio = false))
        assertFalse(MediaTransfer.verify(asset("audio-truncated.m4a"), requireAudio = false))
        try {
            MediaTransfer.mux(asset("video-truncated.mp4"), asset("audio.m4a"), broken) { false }
        } catch (_: BridgeException) {
            assertFalse("berkas gagal tidak boleh lolos verifikasi", MediaTransfer.verify(broken, true))
            return
        }
        // A device that tolerates the truncated header must still not produce a usable file.
        assertFalse(MediaTransfer.verify(broken, requireAudio = true))
    }

    @Test
    fun cancelDuringMuxCommitsNothing() {
        val canceled = output("canceled.mp4")
        var calls = 0
        try {
            MediaTransfer.mux(asset("video.mp4"), asset("audio.m4a"), canceled) { calls++ > 0 }
            fail("mux yang dibatalkan harus melempar")
        } catch (error: BridgeException) {
            assertEquals("downloadCanceled", error.code)
        }
        assertTrue("mux harus berhenti setelah beberapa sampel", calls > 0)
        assertFalse(MediaTransfer.verify(canceled, requireAudio = true))
    }

    private fun assertWithinTolerance(sourceUs: Long, mergedUs: Long) {
        assertTrue(
            "durasi $mergedUs us di luar toleransi dari $sourceUs us",
            abs(sourceUs - mergedUs) <= TIMING_TOLERANCE_US,
        )
    }

    private fun trackCount(file: File): Int {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            return extractor.trackCount
        } finally {
            extractor.release()
        }
    }

    private fun stats(file: File, mime: String): TrackStat {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == mime
            } ?: return TrackStat(0, 0L, emptyList())
            val format = extractor.getTrackFormat(index)
            extractor.selectTrack(index)
            val buffer = ByteBuffer.allocateDirect(1024 * 1024)
            val pts = ArrayList<Long>()
            while (extractor.readSampleData(buffer, 0) >= 0) {
                pts += extractor.sampleTime
                if (!extractor.advance()) break
            }
            val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else {
                pts.lastOrNull() ?: 0L
            }
            return TrackStat(pts.size, duration, pts)
        } finally {
            extractor.release()
        }
    }
}
