package com.nonton.yuks

import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaTransferTest {
    private fun asset(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = File(instrumentation.targetContext.cacheDir, "recovery-$name")
        instrumentation.context.assets.open(name).use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        return target
    }

    @Test
    fun completedSeparateTracksAreRecoverableWithoutMuxing() {
        assertTrue(MediaTransfer.completeTrack(asset("video.mp4"), MediaFormat.MIMETYPE_VIDEO_AVC))
        assertTrue(MediaTransfer.completeTrack(asset("audio.m4a"), MediaFormat.MIMETYPE_AUDIO_AAC))
        assertTrue(MediaTransfer.verify(asset("video.mp4"), requireAudio = false))
        assertFalse(MediaTransfer.verify(asset("video.mp4"), requireAudio = true))
    }

    @Test
    fun truncatedOrWrongTracksNeverBecomeAnOfflineCollectionEntry() {
        assertFalse(MediaTransfer.completeTrack(asset("video-truncated.mp4"), MediaFormat.MIMETYPE_VIDEO_AVC))
        assertFalse(MediaTransfer.completeTrack(asset("audio-truncated.m4a"), MediaFormat.MIMETYPE_AUDIO_AAC))
        assertFalse(MediaTransfer.completeTrack(asset("audio.m4a"), MediaFormat.MIMETYPE_VIDEO_AVC))
        assertFalse(MediaTransfer.completeTrack(asset("video.mp4"), MediaFormat.MIMETYPE_AUDIO_AAC))
    }
}
