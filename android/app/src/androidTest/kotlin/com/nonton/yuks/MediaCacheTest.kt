package com.nonton.yuks

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaCacheTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var root: File
    private lateinit var engine: CacheEngine
    private lateinit var server: FakeMediaServer
    private val databases = ArrayList<StandaloneDatabaseProvider>()

    @Before fun setUp() {
        root = File(instrumentation.targetContext.cacheDir, "cache-test-" + UUID.randomUUID())
        assertTrue(root.mkdirs())
        server = FakeMediaServer()
        engine = openEngine()
    }

    @After fun tearDown() {
        engine.close()
        databases.forEach { it.close() }
        root.deleteRecursively()
    }

    private fun openEngine(): CacheEngine {
        val database = StandaloneDatabaseProvider(instrumentation.targetContext).also { databases.add(it) }
        return CacheEngine(root, SimpleCache(File(root, "bytes"), NoOpCacheEvictor(), database), server)
    }

    private fun reopen() { engine.close(); engine = openEngine() }

    private fun descriptor(vararg tracks: Pair<String, List<String>>): Resolved = Resolved(
        platform = "youtube", videoId = "cache_test1", sourceUrl = "https://www.youtube.com/watch?v=cache_test1",
        title = "Cache test", uploader = null, durationMs = 1_000L, thumbnailUrl = null, height = 360,
        heights = listOf(360), approxBytes = 0L,
        streams = tracks.map { (kind, parts) -> Stream(kind, parts, mapOf("User-Agent" to "CacheTest"), 0L) },
    )

    private fun resource(name: String, size: Int): String = server.add(name, ByteArray(size) { ((it * 31 + name.length) % 251).toByte() })
    private fun uri(): Uri = Uri.parse("cache://test/track")
    private fun read(track: Int, position: Long = 0L, length: Long = C.LENGTH_UNSET.toLong()): ByteArray {
        val source = engine.session("task").stream(track)
        try {
            source.open(DataSpec.Builder().setUri(uri()).setPosition(position).setLength(length).build())
            assertEquals("signed media URLs must remain behind the virtual source", uri(), source.uri)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = source.read(buffer, 0, buffer.size)
                if (count < 0) return out.toByteArray()
                out.write(buffer, 0, count)
            }
        } finally { source.close() }
    }

    @Test fun splitTracksFillInterleavedAndResumeOfflineAfterReopen() {
        val video = resource("video", (CACHE_CHUNK * 3 + 17).toInt())
        val audio = resource("audio", (CACHE_CHUNK * 2 + 31).toInt())
        val total = server.data[video]!!.size.toLong() + server.data[audio]!!.size
        val progress = ArrayList<Triple<Long, Long, Boolean>>()
        engine.newJob("task", descriptor("video" to listOf(video), "audio" to listOf(audio))) { bytes, known, playable ->
            progress.add(Triple(bytes, known, playable))
        }.run()
        assertEquals(listOf(video, audio), server.requests.take(2).map { it.url })
        assertTrue(server.requests.take(2).all { it.length <= CACHE_CHUNK })
        assertTrue(server.requests.all { it.length in 1..CACHE_WINDOW })
        assertTrue(server.requests.drop(2).any { it.length > CACHE_CHUNK })
        assertTrue(progress.any { it.third && it.first < total })
        assertEquals(total, engine.bytes("task"))
        assertTrue(engine.isComplete("task"))
        assertEquals(total, server.served.get())
        reopen()
        server.online = false
        assertTrue(engine.isComplete("task"))
        assertTrue(engine.isPlayable("task"))
        val opened = server.requests.size
        assertArrayEquals(server.data[video], read(0))
        assertArrayEquals(server.data[audio], read(1))
        assertEquals("completed playback cannot touch upstream", opened, server.requests.size)
    }

    @Test fun ignoredRangesKeepOneResponsePerTrackAndCompleteOffline() {
        server.ignoreRanges = true
        val video = resource("no-range-video", (CACHE_CHUNK * 4 + 19).toInt())
        val audio = resource("no-range-audio", (CACHE_CHUNK * 3 + 7).toInt())
        engine.newJob("task", descriptor("video" to listOf(video), "audio" to listOf(audio))) { _, _, _ -> }.run()
        val total = server.data[video]!!.size.toLong() + server.data[audio]!!.size
        assertEquals(total, engine.bytes("task"))
        assertEquals(total, server.served.get())
        assertEquals(listOf(video, audio), server.requests.map { it.url })
        assertTrue(engine.isComplete("task"))
        reopen()
        server.online = false
        assertArrayEquals(server.data[video], read(0))
        assertArrayEquals(server.data[audio], read(1))
    }

    @Test fun ignoredRangeResumeRetainsProgressInsteadOfTruncatingCache() {
        server.ignoreRanges = true
        val media = resource("no-range-resume", (CACHE_CHUNK * 3 + 13).toInt())
        lateinit var job: CacheJob
        job = engine.newJob("task", descriptor("muxed" to listOf(media))) { bytes, _, _ ->
            if (bytes >= CACHE_CHUNK) job.cancel()
        }
        try { job.run(); fail("pause expected") }
        catch (error: BridgeException) { assertEquals("downloadCanceled", error.code) }
        val saved = engine.bytes("task")
        assertEquals(CACHE_CHUNK, saved)
        reopen()
        assertEquals(saved, engine.bytes("task"))
        engine.newJob("task", null) { _, _, _ -> }.run()
        assertEquals(saved, server.requests.last().position)
        assertEquals(server.data[media]!!.size.toLong(), engine.bytes("task"))
        // HTTP 200 must resend its prefix, but stored bytes and progress never reset.
        assertEquals(server.data[media]!!.size.toLong() + saved, server.served.get())
        assertTrue(engine.isComplete("task"))
        server.online = false
        assertArrayEquals(server.data[media], read(0))
    }

    @Test fun ignoredRangePlaybackWaitsForCacheRatherThanOpeningAnotherFullResponse() {
        server.ignoreRanges = true
        server.blockAfter = 64 * 1024L
        val media = resource("no-range-shared", (CACHE_CHUNK * 3).toInt())
        val job = engine.newJob("task", descriptor("muxed" to listOf(media))) { _, _, _ -> }
        val failure = AtomicReference<Throwable?>()
        val worker = Thread { try { job.run() } catch (error: Throwable) { failure.set(error) } }
        worker.start()
        assertTrue(server.blocked.await(5, TimeUnit.SECONDS))
        val playerStarted = CountDownLatch(1)
        val playerFailure = AtomicReference<Throwable?>()
        val player = Thread {
            playerStarted.countDown()
            try { read(0, CACHE_CHUNK * 2, 1L) }
            catch (error: Throwable) { playerFailure.set(error) }
        }
        player.start()
        assertTrue(playerStarted.await(5, TimeUnit.SECONDS))
        job.cancel()
        worker.join(5_000)
        player.join(5_000)
        assertFalse(worker.isAlive)
        assertFalse(player.isAlive)
        assertTrue(playerFailure.get() is IOException)
        assertEquals(1, server.requests.size)
        assertEquals(64 * 1024L, engine.bytes("task"))
    }

    @Test fun orderedInitAndFragmentsAreOneSeekableStream() {
        val init = resource("init", 19)
        val first = resource("fragment1", 73)
        val last = resource("fragment2", (CACHE_CHUNK + 37).toInt())
        engine.newJob("task", descriptor("muxed" to listOf(init, first, last))) { _, _, _ -> }.run()
        val expected = server.data[init]!! + server.data[first]!! + server.data[last]!!
        reopen()
        server.online = false
        assertArrayEquals(expected, read(0))
        assertArrayEquals(expected.copyOfRange(13, 107), read(0, 13L, 94L))
        assertEquals(expected.size.toLong(), engine.bytes("task"))
        assertTrue(engine.isComplete("task"))
    }

    @Test fun processReopenRecoversSpanWrittenBeforeCacheCommit() {
        val media = resource("video", (CACHE_CHUNK * 3).toInt())
        engine.newJob("task", descriptor("muxed" to listOf(media))) { _, _, _ -> }
        val session = engine.session("task")
        session.metadata(0, 0, CacheResponse(server.data[media]!!.size.toLong(), server.etag, null))
        engine.cache.startReadWrite(session.key(0, 0), 0L, CACHE_CHUNK)
        val pending = engine.cache.startFile(session.key(0, 0), 0L, CACHE_CHUNK)
        java.io.FileOutputStream(pending).use { output ->
            output.write(server.data[media]!!, 0, 64 * 1024)
            output.fd.sync()
        }
        // Abandoned hole/file models process death before a writer's close/commit finally block.
        reopen()
        assertEquals(64 * 1024L, engine.bytes("task"))
        assertFalse(engine.isComplete("task"))
        engine.newJob("task", null) { _, _, _ -> }.run()
        assertEquals(64 * 1024L, server.requests.first().position)
        assertArrayEquals(server.data[media], read(0))
        assertEquals(server.data[media]!!.size - 64 * 1024L, server.served.get())
    }

    @Test fun pauseCancelsSocketsAndCommitsPartialChunkWithoutTruncation() {
        val media = resource("video", (CACHE_CHUNK * 3).toInt())
        server.blockAfter = 64 * 1024L
        val job = engine.newJob("task", descriptor("muxed" to listOf(media))) { _, _, _ -> }
        val failure = AtomicReference<Throwable?>()
        val worker = Thread { try { job.run() } catch (error: Throwable) { failure.set(error) } }
        worker.start()
        assertTrue(server.blocked.await(5, TimeUnit.SECONDS))
        val playerOpened = CountDownLatch(1)
        val playerFailure = AtomicReference<Throwable?>()
        server.onOpen = { if (it.position == CACHE_CHUNK * 2) playerOpened.countDown() }
        val player = Thread {
            try { read(0, CACHE_CHUNK * 2, 1L) }
            catch (error: Throwable) { playerFailure.set(error) }
        }
        player.start()
        assertTrue(playerOpened.await(5, TimeUnit.SECONDS))
        job.cancel()
        worker.join(5_000)
        player.join(5_000)
        assertFalse(player.isAlive)
        assertTrue(playerFailure.get() is IOException)
        assertFalse(worker.isAlive)
        assertEquals("downloadCanceled", (failure.get() as BridgeException).code)
        val saved = engine.bytes("task")
        assertEquals(64 * 1024L, saved)
        val opened = server.requests.size
        assertArrayEquals(server.data[media]!!.copyOfRange(0, saved.toInt()), read(0, 0L, saved))
        try { read(0, saved, 1L); fail("paused cache miss must not be EOF or use network") }
        catch (error: IOException) { assertEquals(CACHE_PAUSED, error.message) }
        assertEquals(opened, server.requests.size)
        reopen()
        assertEquals(saved, engine.bytes("task"))
        server.blockAfter = Long.MAX_VALUE
        engine.newJob("task", null) { _, _, _ -> }.run()
        assertEquals(saved, server.requests[opened].position)
        assertArrayEquals(server.data[media], read(0))
        assertEquals(server.data[media]!!.size.toLong(), server.served.get())
    }

    @Test fun ioFailureKeepsCommittedBytesAndSameSnapshot() {
        val media = resource("video", (CACHE_CHUNK * 3).toInt())
        server.failAfter = CACHE_CHUNK + 64 * 1024L
        try { engine.newJob("task", descriptor("muxed" to listOf(media))) { _, _, _ -> }.run(); fail("I/O failure expected") }
        catch (error: BridgeException) { assertEquals("downloadFailed", error.code) }
        val saved = engine.bytes("task")
        assertEquals(CACHE_CHUNK + 64 * 1024L, saved)
        assertTrue(engine.exists("task"))
        assertFalse(engine.isComplete("task"))
        reopen()
        assertEquals(saved, engine.bytes("task"))
        server.failAfter = Long.MAX_VALUE
        engine.newJob("task", null) { _, _, _ -> }.run()
        assertTrue(engine.isComplete("task"))
        assertEquals(server.data[media]!!.size.toLong(), server.served.get())
    }

    @Test fun prematureEofNeverCompletesAndResumeRestoresOnlyMissingBytes() {
        val media = resource("video", (CACHE_CHUNK * 2).toInt())
        server.shortAfter = 64 * 1024L
        try { engine.newJob("task", descriptor("muxed" to listOf(media))) { _, _, _ -> }.run(); fail("short body expected") }
        catch (error: BridgeException) { assertEquals("downloadFailed", error.code) }
        assertFalse(engine.isComplete("task"))
        assertEquals(64 * 1024L, engine.bytes("task"))
        reopen()
        server.shortAfter = Long.MAX_VALUE
        engine.newJob("task", null) { _, _, _ -> }.run()
        assertTrue(engine.isComplete("task"))
        assertArrayEquals(server.data[media], read(0))
        assertEquals(server.data[media]!!.size.toLong(), server.served.get())
    }

    @Test fun changedRepresentationFailsWithoutDiscardingOrMixing() {
        val media = resource("video", (CACHE_CHUNK * 3).toInt())
        lateinit var job: CacheJob
        job = engine.newJob("task", descriptor("muxed" to listOf(media))) { bytes, _, _ -> if (bytes >= CACHE_CHUNK) job.cancel() }
        try { job.run() } catch (_: BridgeException) { }
        val saved = engine.bytes("task")
        val original = read(0, 0L, saved)
        reopen()
        server.etag = "\"different\""
        try { engine.newJob("task", null) { _, _, _ -> }.run(); fail("representation drift must fail") }
        catch (error: BridgeException) { assertEquals(CACHE_CHANGED, error.message) }
        assertEquals(saved, engine.bytes("task"))
        assertArrayEquals(original, read(0, 0L, saved))
        try {
            engine.newJob("task", descriptor("muxed" to listOf(resource("replacement", 20)))) { _, _, _ -> }
            fail("a replacement descriptor cannot overwrite saved bytes")
        } catch (error: BridgeException) { assertEquals(CACHE_CHANGED, error.message) }
        assertEquals(saved, engine.bytes("task"))
    }

    @Test fun downloaderAndPlayerShareHoleLocksRatherThanDuplicatingStreams() {
        val media = resource("video", (CACHE_CHUNK * 3).toInt())
        server.blockAfter = 64 * 1024L
        val failure = AtomicReference<Throwable?>()
        val job = engine.newJob("task", descriptor("muxed" to listOf(media))) { _, _, _ -> }
        val worker = Thread { try { job.run() } catch (error: Throwable) { failure.set(error) } }
        worker.start()
        assertTrue(server.blocked.await(5, TimeUnit.SECONDS))
        val playerResult = AtomicReference<ByteArray?>()
        val readerStarted = CountDownLatch(1)
        val player = Thread {
            readerStarted.countDown()
            try { playerResult.set(read(0)) } catch (error: Throwable) { failure.set(error) }
        }
        player.start()
        assertTrue(readerStarted.await(5, TimeUnit.SECONDS))
        server.blockAfter = Long.MAX_VALUE
        server.release.countDown()
        worker.join(5_000)
        player.join(5_000)
        assertFalse(worker.isAlive)
        assertFalse(player.isAlive)
        failure.get()?.let { throw AssertionError(it) }
        assertArrayEquals(server.data[media], playerResult.get())
        assertEquals(server.data[media]!!.size.toLong(), server.served.get())
        assertEquals(server.data[media]!!.size.toLong(), engine.bytes("task"))
    }

    @Test fun removeAffectsOnlySelectedTask() {
        val first = resource("first", 97)
        val second = resource("second", 113)
        engine.newJob("task", descriptor("muxed" to listOf(first))) { _, _, _ -> }.run()
        engine.newJob("other", descriptor("muxed" to listOf(second))) { _, _, _ -> }.run()
        val otherMarker = engine.manifestPath("other")
        engine.remove("task")
        assertFalse(engine.exists("task"))
        assertTrue(engine.exists("other"))
        assertTrue(File(otherMarker).isFile)
        assertEquals(113L, engine.bytes("other"))
        assertTrue(engine.isComplete("other"))
    }

    @Test fun nativeMergedSourceReachesReadyWithBothTracksOfflineAfterReopen() {
        val video = server.add("video", instrumentation.context.assets.open("video.mp4").use { it.readBytes() })
        val audio = server.add("audio", instrumentation.context.assets.open("audio.m4a").use { it.readBytes() })
        engine.newJob("task", descriptor("video" to listOf(video), "audio" to listOf(audio))) { _, _, _ -> }.run()
        reopen()
        server.online = false
        assertPlaysBothTracks()
    }

    @Test fun nativeMergedSourceCanStartBeforeDownloadCompletion() {
        fun paddedAsset(name: String): ByteArray {
            val bytes = instrumentation.context.assets.open(name).use { it.readBytes() }
            val padding = ByteArray((CACHE_CHUNK * 8).toInt())
            ByteBuffer.wrap(padding).putInt(padding.size).put("free".toByteArray(Charsets.US_ASCII))
            return bytes + padding
        }
        val video = server.add("video", paddedAsset("video.mp4"))
        val audio = server.add("audio", paddedAsset("audio.m4a"))
        val buffered = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val job = engine.newJob("task", descriptor("video" to listOf(video), "audio" to listOf(audio))) { bytes, _, playable ->
            if (playable && buffered.count > 0L) {
                server.blockAfter = bytes
                buffered.countDown()
            }
        }
        val worker = Thread { try { job.run() } catch (error: Throwable) { failure.set(error) } }
        worker.start()
        try {
            assertTrue(buffered.await(5, TimeUnit.SECONDS))
            assertTrue(server.blocked.await(5, TimeUnit.SECONDS))
            assertTrue(engine.isPlayable("task"))
            assertFalse(engine.isComplete("task"))
            assertTrue(engine.bytes("task") < server.data.values.sumOf { it.size.toLong() })
            assertPlaysBothTracks()
            assertTrue("downloader still active while player advances", job.running)
            assertFalse(engine.isComplete("task"))
        } finally {
            job.cancel()
            worker.join(5_000)
        }
        assertFalse(worker.isAlive)
        assertEquals("downloadCanceled", (failure.get() as BridgeException).code)
    }

    private fun assertPlaysBothTracks() {
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val advanced = AtomicBoolean(false)
        lateinit var player: ExoPlayer
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(instrumentation.targetContext).build()
            player.addListener(object : Player.Listener {
                private var scheduled = false
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY && !scheduled) {
                        scheduled = true
                        Handler(Looper.getMainLooper()).postDelayed({
                            val groups = player.currentTracks.groups
                            advanced.set(player.currentPosition > 0L && groups.any { it.type == C.TRACK_TYPE_VIDEO } &&
                                groups.any { it.type == C.TRACK_TYPE_AUDIO })
                            completed.countDown()
                        }, 400L)
                    }
                }
                override fun onPlayerError(error: PlaybackException) { failure.set(error); completed.countDown() }
            })
            player.setMediaSource(engine.source("task"))
            player.playWhenReady = true
            player.prepare()
        }
        try {
            assertTrue("native player must reach READY", completed.await(15, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("cache playback failed", it) }
            assertTrue("audio/video selected and playback position advances", advanced.get())
        } finally { instrumentation.runOnMainSync { player.release() } }
    }
}

private data class FakeRequest(val url: String, val position: Long, val length: Long)

private class FakeMediaServer : CacheUpstreamFactory {
    val data = LinkedHashMap<String, ByteArray>()
    val requests: MutableList<FakeRequest> = Collections.synchronizedList(ArrayList())
    val served = AtomicLong()
    val blocked = CountDownLatch(1)
    val release = CountDownLatch(1)
    @Volatile var online = true
    @Volatile var etag = "\"original\""
    @Volatile var blockAfter = Long.MAX_VALUE
    @Volatile var failAfter = Long.MAX_VALUE
    @Volatile var shortAfter = Long.MAX_VALUE
    @Volatile var ignoreRanges = false
    @Volatile var onOpen: (DataSpec) -> Unit = {}
    fun add(name: String, bytes: ByteArray): String = "https://media.googlevideo.com/$name".also { data[it] = bytes }

    override fun create(request: CacheUpstreamRequest): DataSource {
        check(online) { "offline playback must not construct an upstream" }
        return object : DataSource, CancelableUpstream {
        @Volatile private var canceled = false
        private var uri: Uri? = null
        private lateinit var payload: ByteArray
        private var position = 0
        private var remaining = 0L
        override fun addTransferListener(listener: TransferListener) { }
        override fun getUri(): Uri? = uri
        override fun open(dataSpec: DataSpec): Long {
            if (!online || canceled) throw IOException("fake upstream disabled")
            uri = dataSpec.uri
            payload = data[dataSpec.uri.toString()] ?: throw IOException("unknown fixture")
            requests.add(FakeRequest(dataSpec.uri.toString(), dataSpec.position, dataSpec.length))
            onOpen(dataSpec)
            request.onResponse(CacheResponse(payload.size.toLong(), etag, null, !ignoreRanges))
            position = dataSpec.position.toInt()
            if (position > payload.size) throw IOException("invalid fixture range")
            val available = (payload.size - position).toLong()
            if (ignoreRanges) served.addAndGet(position.toLong())
            remaining = if (ignoreRanges || dataSpec.length < 0L) available else min(dataSpec.length, available)
            return remaining
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (served.get() >= blockAfter) {
                blocked.countDown()
                if (!release.await(15, TimeUnit.SECONDS)) throw IOException("fixture blocked")
            }
            if (canceled || !online || served.get() >= failAfter) throw IOException("fake socket stopped")
            if (remaining <= 0 || served.get() >= shortAfter) return C.RESULT_END_OF_INPUT
            val count = min(length.toLong(), min(remaining, min(64 * 1024L, shortAfter - served.get()))).toInt()
            payload.copyInto(buffer, offset, position, position + count)
            position += count
            remaining -= count
            served.addAndGet(count.toLong())
            return count
        }
        override fun cancel() { canceled = true; release.countDown() }
        override fun close() { uri = null }
    }
    }
}
