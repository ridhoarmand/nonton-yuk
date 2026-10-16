package com.nonton.yuks

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min

object MediaCache {
    private var instance: CacheEngine? = null

    @Synchronized
    private fun engine(context: Context): CacheEngine {
        instance?.let { return it }
        val root = File(context.applicationContext.filesDir, "media-cache-v1")
        check(root.isDirectory || root.mkdirs())
        return CacheEngine(root, SimpleCache(File(root, "bytes"), NoOpCacheEvictor(),
            StandaloneDatabaseProvider(context.applicationContext))).also { instance = it }
    }

    fun exists(context: Context, id: String): Boolean = engine(context).exists(id)
    fun bytes(context: Context, id: String): Long = engine(context).bytes(id)
    fun isPlayable(context: Context, id: String): Boolean = engine(context).isPlayable(id)
    fun isComplete(context: Context, id: String): Boolean = engine(context).isComplete(id)
    fun manifestPath(context: Context, id: String): String = engine(context).manifestPath(id)
    fun source(context: Context, id: String): MediaSource = engine(context).source(id)
    fun remove(context: Context, id: String) = engine(context).remove(id)
    fun newJob(context: Context, id: String, resolved: Resolved?,
        onProgress: (bytes: Long, total: Long, playable: Boolean) -> Unit): Job =
        Job(engine(context).newJob(id, resolved, onProgress))
    fun newRefreshJob(context: Context, id: String, resolve: (String, Int) -> Resolved): RefreshJob =
        RefreshJob(engine(context).newRefreshJob(id, resolve))

    class Job internal constructor(private val delegate: CacheJob) {
        fun run() = delegate.run()
        fun cancel() = delegate.cancel()
    }

    class RefreshJob internal constructor(private val delegate: CacheRefreshJob) {
        fun run(): Resolved = delegate.run()
        fun cancel() = delegate.cancel()
    }
}

internal const val CACHE_CHUNK = 256 * 1024L
internal const val CACHE_WINDOW = 2 * 1024 * 1024L
private const val START_BUFFER = 64 * 1024L
private const val MANIFEST_LIMIT = 8 * 1024 * 1024L
private const val BAD_CACHE = "Data unduhan tidak dapat dibuka. Byte lama tetap tersimpan; jangan hapus jika masih diperlukan."
private const val REFRESH_BUSY = "Tutup pemutar video ini sebelum memperbarui link."
private const val REFRESH_MISMATCH = "Tautan baru tidak cocok dengan berkas tersimpan. Data lama tidak diubah."
private const val VIDEO_CHANGED = "Video pada tautan ini sudah berbeda. Unduhan tersimpan tidak diubah."

internal data class CachePart(val url: String, var length: Long = -1L,
    var etag: String? = null, var lastModified: String? = null,
    @Volatile var rangeSupported: Boolean? = null)
internal data class CacheTrack(val kind: String, val headers: Map<String, String>, val parts: List<CachePart>)
internal data class CacheSnapshot(val platform: String, val videoId: String, val height: Int, val tracks: List<CacheTrack>) {
    fun json(): JSONObject = JSONObject().put("version", 1).put("platform", platform)
        .put("videoId", videoId).put("height", height).put("tracks", JSONArray().also { array ->
            tracks.forEach { track -> array.put(JSONObject().put("kind", track.kind)
                .put("headers", JSONObject(track.headers)).put("parts", JSONArray().also { parts ->
                    track.parts.forEach { part -> parts.put(JSONObject().put("url", part.url)
                        .put("length", part.length).put("etag", part.etag).put("lastModified", part.lastModified)
                        .put("rangeSupported", part.rangeSupported)) }
                })) }
        })

    companion object {
        fun from(resolved: Resolved): CacheSnapshot = parse(JSONObject().put("version", 1)
            .put("platform", resolved.platform).put("videoId", resolved.videoId).put("height", resolved.height)
            .put("tracks", JSONArray().also { array -> resolved.streams.forEach { track ->
                array.put(JSONObject().put("kind", track.kind).put("headers", JSONObject(track.headers))
                    .put("parts", JSONArray().also { parts -> track.parts.forEach { url ->
                        parts.put(JSONObject().put("url", url).put("length", -1L))
                    } }))
            } }))

        fun parse(json: JSONObject): CacheSnapshot {
            if (json.getInt("version") != 1) throw IOException(BAD_CACHE)
            val platform = json.getString("platform")
            val videoId = json.getString("videoId")
            val height = json.getInt("height")
            if (!Platforms.known(platform) || !Regex("[A-Za-z0-9_-]{1,128}").matches(videoId) || height < 0) throw IOException(BAD_CACHE)
            val array = json.getJSONArray("tracks")
            if (array.length() !in 1..2) throw IOException(BAD_CACHE)
            val tracks = (0 until array.length()).map { index ->
                val track = array.getJSONObject(index)
                val headersJson = track.getJSONObject("headers")
                val headers = headersJson.keys().asSequence().associateWith { headersJson.getString(it) }
                PinnedHttpPolicy.headers(headers)
                val parts = track.getJSONArray("parts")
                if (parts.length() !in 1..128) throw IOException(BAD_CACHE)
                CacheTrack(track.getString("kind"), headers, (0 until parts.length()).map { partIndex ->
                    val part = parts.getJSONObject(partIndex)
                    val url = part.getString("url")
                    PinnedHttpPolicy.uri(url, platform)
                    val length = part.getLong("length")
                    if (length < -1L) throw IOException(BAD_CACHE)
                    val etag = part.optString("etag").takeIf { part.has("etag") && !part.isNull("etag") }
                    if (etag != null && PinnedHttpPolicy.strongEtag(etag) != etag) throw IOException(BAD_CACHE)
                    val modified = part.optString("lastModified").takeIf { part.has("lastModified") && !part.isNull("lastModified") }
                    if (modified != null && (modified.length !in 1..128 || modified.any { it.code !in 0x20..0x7e })) throw IOException(BAD_CACHE)
                    val range = if (part.has("rangeSupported") && !part.isNull("rangeSupported")) {
                        part.get("rangeSupported") as? Boolean ?: throw IOException(BAD_CACHE)
                    } else null
                    CachePart(url, length, etag, modified, range)
                })
            }
            if (!(tracks.size == 1 && tracks[0].kind == "muxed" ||
                    tracks.size == 2 && tracks.map { it.kind }.toSet() == setOf("video", "audio"))) throw IOException(BAD_CACHE)
            return CacheSnapshot(platform, videoId, height, tracks)
        }
    }
}

internal data class CacheUpstreamRequest(val platform: String, val headers: Map<String, String>,
    val expected: CacheResponse, val onResponse: (CacheResponse) -> Unit)
internal fun interface CacheUpstreamFactory { fun create(request: CacheUpstreamRequest): DataSource }

internal class CacheEngine(
    private val root: File,
    internal val cache: SimpleCache,
    private val upstreamFactory: CacheUpstreamFactory = CacheUpstreamFactory { request ->
        PinnedHttpDataSource(request.platform, request.headers, request.expected, request.onResponse,
            keepSequentialBody = true)
    },
) {
    private val sessions = ConcurrentHashMap<String, CacheSession>()
    private val directory = File(root, "manifests").also { check(it.isDirectory || it.mkdirs()) }
    private var closed = false

    internal fun taskKey(id: String): String {
        if (id.isEmpty() || id.length > 256 || id.any { it.code < 0x20 }) throw BridgeException("notFound", "Unduhan tidak ditemukan.")
        return MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun manifestPath(id: String): String = File(directory, taskKey(id) + ".json").absolutePath
    private fun manifest(id: String): AtomicFile = AtomicFile(File(manifestPath(id)))
    fun exists(id: String): Boolean = manifest(id).let { it.baseFile.isFile || File(it.baseFile.path + ".bak").isFile }

    @Synchronized
    internal fun session(id: String): CacheSession {
        if (closed) throw IOException(CACHE_PAUSED)
        sessions[id]?.let { return it }
        val snapshot = try {
            manifest(id).openRead().use { input ->
                if (input.channel.size() > MANIFEST_LIMIT) throw IOException(BAD_CACHE)
                CacheSnapshot.parse(JSONObject(input.bufferedReader(Charsets.UTF_8).readText()))
            }
        } catch (_: Exception) { throw BridgeException("downloadFailed", BAD_CACHE) }
        return CacheSession(this, id, snapshot).also { sessions[id] = it }
    }

    @Synchronized
    fun newJob(id: String, resolved: Resolved?, onProgress: (Long, Long, Boolean) -> Unit): CacheJob {
        if (closed) throw IOException(CACHE_PAUSED)
        if (resolved != null) {
            if (exists(id)) throw BridgeException("downloadFailed", CACHE_CHANGED)
            val snapshot = CacheSnapshot.from(resolved)
            save(id, snapshot)
            sessions[id] = CacheSession(this, id, snapshot)
        }
        val session = session(id)
        return session.newJob(onProgress)
    }

    fun newRefreshJob(id: String, resolve: (String, Int) -> Resolved): CacheRefreshJob =
        CacheRefreshJob(this, id, resolve)

    internal fun reserveRefresh(job: CacheRefreshJob): CacheSession {
        val (session, cancel) = synchronized(this) {
            job.checkCanceled()
            if (job.started) throw IOException(CACHE_PAUSED)
            job.started = true
            val session = session(job.id)
            val cancel = session.reserveRefresh(job)
            job.session = session
            session to cancel
        }
        cancel.forEach { runCatching { it() } }
        job.checkCanceled()
        return session
    }

    @Synchronized
    internal fun attachRefresh(job: CacheRefreshJob, source: DataSource) {
        checkRefresh(job)
        job.upstream = source
    }

    @Synchronized
    internal fun detachRefresh(job: CacheRefreshJob, source: DataSource) {
        if (job.upstream === source) job.upstream = null
    }

    private fun checkRefresh(job: CacheRefreshJob): CacheSession {
        job.checkCanceled()
        val session = job.session
        if (closed || session == null || sessions[job.id] !== session ||
            !session.ownsRefresh(job) || !exists(job.id)) throw BridgeException("downloadCanceled", CACHE_PAUSED)
        return session
    }

    @Synchronized
    internal fun commitRefresh(job: CacheRefreshJob, candidate: CacheSnapshot) {
        val previous = checkRefresh(job)
        try { save(job.id, candidate) } catch (_: IOException) { throw BridgeException("downloadFailed", BAD_CACHE) }
        previous.retireRefresh(job)
        sessions[job.id] = CacheSession(this, job.id, candidate)
        job.committed = true
    }

    private fun cancelRefreshLocked(job: CacheRefreshJob): DataSource? {
        if (job.committed) return null
        job.canceled = true
        job.session?.releaseRefresh(job)
        return job.upstream.also { job.upstream = null }
    }

    internal fun cancelRefresh(job: CacheRefreshJob) {
        val source = synchronized(this) { cancelRefreshLocked(job) }
        cancelUpstream(source)
    }

    @Synchronized
    internal fun releaseRefresh(job: CacheRefreshJob) {
        job.session?.releaseRefresh(job)
    }

    internal fun save(id: String, snapshot: CacheSnapshot) {
        val file = manifest(id)
        val output = file.startWrite()
        try {
            output.write(snapshot.json().toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw IOException(BAD_CACHE)
        }
    }

    fun bytes(id: String): Long = if (exists(id)) session(id).bytes() else 0L
    fun isPlayable(id: String): Boolean = exists(id) && session(id).playable()
    fun isComplete(id: String): Boolean = exists(id) && session(id).complete()
    @Synchronized
    fun source(id: String): MediaSource {
        val session = session(id)
        session.checkPresent()
        val sources = session.snapshot.tracks.mapIndexed { index, _ ->
            ProgressiveMediaSource.Factory(DataSource.Factory { session.stream(index) })
                .createMediaSource(MediaItem.fromUri("cache://" + taskKey(id) + "/" + index))
        }
        return if (sources.size == 1) sources.single() else MergingMediaSource(*sources.toTypedArray())
    }

    fun remove(id: String) {
        val source = synchronized(this) {
            val session = sessions.remove(id)
            val source = session?.refreshJob()?.let { cancelRefreshLocked(it) }
            session?.remove()
            val prefix = "ny-v1:" + taskKey(id) + ":"
            cache.keys.filter { it.startsWith(prefix) }.forEach { cache.removeResource(it) }
            manifest(id).delete()
            source
        }
        cancelUpstream(source)
    }

    internal fun upstream(request: CacheUpstreamRequest): DataSource = upstreamFactory.create(request)

    internal fun close() {
        val retired = synchronized(this) {
            closed = true
            sessions.values.map { session ->
                val source = session.refreshJob()?.let { cancelRefreshLocked(it) }
                session.retire()
                session to source
            }.also { sessions.clear() }
        }
        retired.forEach { (session, source) -> cancelUpstream(source); session.shutdown() }
        cache.release()
    }
}

private fun cancelUpstream(source: DataSource?) {
    runCatching { if (source is CancelableUpstream) source.cancel() else source?.close() }
}

internal class CacheSession(private val engine: CacheEngine, val id: String, val snapshot: CacheSnapshot) {
    private val gate = NetworkGate()
    private val sinks = ConcurrentHashMap.newKeySet<DurableCacheSink>()
    private val readers = ConcurrentHashMap.newKeySet<CacheStreamDataSource>()
    private val windows = ConcurrentHashMap<String, CacheWindowPool>()
    private val progressSignal = Object()
    @Volatile private var removed = false
    @Volatile private var currentJob: CacheJob? = null
    @Volatile private var refresh: CacheRefreshJob? = null
    private val prefix = "ny-v1:" + engine.taskKey(id) + ":"
    internal fun key(track: Int, part: Int): String = prefix + snapshot.tracks[track].kind + ":" + part

    @Synchronized
    fun newJob(onProgress: (Long, Long, Boolean) -> Unit): CacheJob {
        if (refresh != null) throw BridgeException("refreshBusy", REFRESH_BUSY)
        if (removed || currentJob?.running == true) throw BridgeException("downloadFailed", "Unduhan ini sedang berjalan.")
        return CacheJob(this, onProgress).also { currentJob = it }
    }

    @Synchronized
    internal fun begin(job: CacheJob) {
        if (refresh != null) throw BridgeException("refreshBusy", REFRESH_BUSY)
        if (removed || currentJob !== job || job.canceled) throw BridgeException("downloadCanceled", CACHE_PAUSED)
        job.running = true
        gate.enable()
    }

    @Synchronized
    internal fun reserveRefresh(job: CacheRefreshJob): List<() -> Unit> {
        if (removed || refresh != null || currentJob?.running == true || sinks.isNotEmpty() || readers.isNotEmpty()) {
            throw BridgeException("refreshBusy", REFRESH_BUSY)
        }
        refresh = job
        val cancel = gate.detach().toMutableList()
        windows.values.forEach { pool -> cancel.add { pool.close() } }
        windows.clear()
        return cancel
    }

    @Synchronized internal fun ownsRefresh(job: CacheRefreshJob): Boolean = !removed && refresh === job
    @Synchronized internal fun refreshJob(): CacheRefreshJob? = refresh
    @Synchronized internal fun releaseRefresh(job: CacheRefreshJob) { if (refresh === job) refresh = null }
    @Synchronized internal fun retireRefresh(job: CacheRefreshJob) {
        check(refresh === job)
        refresh = null
        removed = true
    }
    @Synchronized internal fun retire() { removed = true; refresh = null }

    @Synchronized
    private fun sinkOpened(sink: DurableCacheSink) {
        checkPresent()
        sinks.add(sink)
    }

    internal fun stop(job: CacheJob) {
        synchronized(this) {
            if (currentJob !== job) return
            gate.disable()
        }
        windows.values.forEach { it.close() }
        signalProgress()
        checkpoint()
    }

    private fun checkpoint() {
        sinks.toList().forEach { it.checkpoint() }
    }

    fun shutdown() {
        gate.disable()
        checkpoint()
        windows.values.forEach { it.close() }
        signalProgress()
        readers.toList().forEach { runCatching { it.close() } }
        sinks.toList().forEach { it.close() }
    }

    fun remove() {
        removed = true
        currentJob?.cancel()
        shutdown()
        snapshot.tracks.forEachIndexed { ti, track -> track.parts.indices.forEach { pi -> engine.cache.removeResource(key(ti, pi)) } }
    }

    @Synchronized
    internal fun metadata(trackIndex: Int, partIndex: Int, response: CacheResponse) {
        val part = snapshot.tracks[trackIndex].parts[partIndex]
        checkPresent()
        if (part.length >= 0L && response.length >= 0L && part.length != response.length ||
            part.etag != null && part.etag != response.etag ||
            part.etag == null && part.lastModified != null && part.lastModified != response.lastModified
        ) throw IOException(CACHE_CHANGED)
        val length = response.length.takeIf { it >= 0 } ?: part.length
        val etag = response.etag ?: part.etag
        val modified = response.lastModified ?: part.lastModified
        if (length != part.length || etag != part.etag || modified != part.lastModified ||
            response.rangeSupported != null && response.rangeSupported != part.rangeSupported) {
            part.length = length
            part.etag = etag
            part.lastModified = modified
            if (response.rangeSupported != null) part.rangeSupported = response.rangeSupported
            engine.save(id, snapshot)
        }
        if (length >= 0L) engine.cache.applyContentMetadataMutations(key(trackIndex, partIndex),
            ContentMetadataMutations().also { ContentMetadataMutations.setContentLength(it, length) })
        signalProgress()
    }

    internal fun dataSource(trackIndex: Int, partIndex: Int): CacheDataSource {
        checkPresent()
        val track = snapshot.tracks[trackIndex]
        val part = track.parts[partIndex]
        val known = length(trackIndex, partIndex)
        if (known >= 0L && ContentMetadata.getContentLength(engine.cache.getContentMetadata(key(trackIndex, partIndex))) != known) {
            engine.cache.applyContentMetadataMutations(key(trackIndex, partIndex),
                ContentMetadataMutations().also { ContentMetadataMutations.setContentLength(it, known) })
        }
        return CacheDataSource.Factory().setCache(engine.cache)
            .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)
            .setCacheWriteDataSinkFactory(DataSink.Factory {
                DurableCacheSink(engine.cache, { sinkOpened(it) },
                    { sinks.remove(it) }, { checkPresent() }, { signalProgress() })
            })
            .setUpstreamDataSourceFactory(DataSource.Factory {
                windows.getOrPut(key(trackIndex, partIndex)) {
                    CacheWindowPool(
                        sequential = { part.rangeSupported == false },
                        factory = {
                            GatedDataSource(gate) {
                                val expected = synchronized(this) {
                                    CacheResponse(part.length, part.etag, part.lastModified, part.rangeSupported)
                                }
                                engine.upstream(CacheUpstreamRequest(snapshot.platform, track.headers, expected,
                                    { response -> metadata(trackIndex, partIndex, response) }))
                            }
                        },
                    )
                }.window()
            }).createDataSource()
    }

    internal fun spec(track: Int, part: Int, offset: Long, length: Long): DataSpec = DataSpec.Builder()
        .setUri(snapshot.tracks[track].parts[part].url).setKey(key(track, part))
        .setPosition(offset).setLength(length).build()

    internal fun checkPresent() {
        if (removed) throw IOException(CACHE_PAUSED)
        if (refresh != null) throw BridgeException("refreshBusy", REFRESH_BUSY)
    }
    @Synchronized
    internal fun stream(trackIndex: Int): DataSource {
        checkPresent()
        return CacheStreamDataSource(this, trackIndex).also { readers.add(it) }
    }
    internal fun readerClosed(reader: CacheStreamDataSource) { readers.remove(reader) }
    @Synchronized
    internal fun readerOpened(reader: CacheStreamDataSource) { checkPresent(); readers.add(reader) }

    internal fun signalProgress() { synchronized(progressSignal) { progressSignal.notifyAll() } }

    internal fun sequentialTrack(track: Int): Boolean =
        snapshot.tracks[track].parts.any { it.rangeSupported == false }

    internal fun awaitSequential(track: Int, part: Int, position: Long, metadataOnly: Boolean = false) {
        while (sequentialTrack(track) && if (metadataOnly) length(track, part) < 0L else
            engine.cache.getCachedLength(key(track, part), position, 1L) <= 0L) {
            checkPresent()
            val job = currentJob
            if (job == null || !job.running || job.canceled) throw IOException(CACHE_PAUSED)
            synchronized(progressSignal) { progressSignal.wait(100L) }
        }
    }

    @Synchronized
    internal fun length(track: Int, part: Int): Long = snapshot.tracks[track].parts[part].length

    fun bytes(): Long = snapshot.tracks.withIndex().sumOf { (ti, track) -> track.parts.indices.sumOf { pi ->
        engine.cache.getCachedBytes(key(ti, pi), 0L, C.LENGTH_UNSET.toLong())
    } }

    @Synchronized
    internal fun total(): Long {
        val parts = snapshot.tracks.flatMap { it.parts }
        if (parts.any { it.length < 0L }) return 0L
        return parts.fold(0L) { total, part -> Math.addExact(total, part.length) }
    }

    internal fun complete(): Boolean = snapshot.tracks.withIndex().all { (ti, track) -> track.parts.indices.all { pi ->
        val length = length(ti, pi)
        length > 0L && engine.cache.isCached(key(ti, pi), 0L, length)
    } }

    internal fun playable(): Boolean = snapshot.tracks.withIndex().all { (ti, track) ->
        var buffered = 0L
        var useful = false
        for (pi in track.parts.indices) {
            val length = length(ti, pi)
            val contiguous = engine.cache.getCachedLength(key(ti, pi), 0L, Long.MAX_VALUE).coerceAtLeast(0L)
            buffered += contiguous
            if (pi > 0 && contiguous > 0L || track.parts.size == 1 && contiguous > 0L) useful = true
            if (length < 0L || contiguous < length) break
        }
        useful && (buffered >= START_BUFFER || track.parts.indices.all { pi ->
            val length = length(ti, pi)
            length > 0L && engine.cache.isCached(key(ti, pi), 0L, length)
        })
    }

    internal fun nextHole(trackIndex: Int): Pair<Int, Long>? {
        snapshot.tracks[trackIndex].parts.indices.forEach { partIndex ->
            val length = length(trackIndex, partIndex)
            var position = 0L
            while (length < 0L || position < length) {
                val cached = engine.cache.getCachedLength(key(trackIndex, partIndex), position,
                    if (length < 0L) Long.MAX_VALUE - position else length - position)
                if (cached <= 0L) return partIndex to position
                position += cached
            }
        }
        return null
    }
}

internal class CacheRefreshJob(
    private val engine: CacheEngine,
    internal val id: String,
    private val resolve: (String, Int) -> Resolved,
) {
    @Volatile internal var canceled = false
    @Volatile internal var committed = false
    internal var started = false
    internal var session: CacheSession? = null
    internal var upstream: DataSource? = null

    fun cancel() = engine.cancelRefresh(this)

    fun run(): Resolved {
        try {
            val previous = engine.reserveRefresh(this)
            checkCanceled()
            val resolved = resolve(previous.snapshot.platform, previous.snapshot.height)
            checkCanceled()
            val candidate = candidate(previous.snapshot, resolved)
            val cached = ByteArray(64 * 1024)
            val remote = ByteArray(64 * 1024)
            candidate.tracks.forEachIndexed { track, value -> value.parts.indices.forEach { part ->
                checkCanceled()
                verifyPart(previous, candidate, track, part, cached, remote)
            } }
            engine.commitRefresh(this, candidate)
            return resolved
        } catch (error: Exception) {
            checkCanceled()
            if (error is BridgeException) throw error
            if (error is IOException && error.message == CACHE_CHANGED) mismatch()
            throw BridgeException("downloadFailed",
                "Link tidak dapat diverifikasi. Data lama tetap tersimpan; coba lagi setelah jaringan pulih.")
        } finally { engine.releaseRefresh(this) }
    }

    internal fun checkCanceled() {
        if (canceled && !committed) throw BridgeException("downloadCanceled", CACHE_PAUSED)
    }

    private fun mismatch(): Nothing = throw BridgeException("refreshMismatch", REFRESH_MISMATCH)

    private fun candidate(previous: CacheSnapshot, resolved: Resolved): CacheSnapshot {
        if (resolved.videoId != previous.videoId) throw BridgeException("videoChanged", VIDEO_CHANGED)
        if (resolved.platform != previous.platform || previous.height > 0 && resolved.height != previous.height ||
            resolved.streams.size != previous.tracks.size ||
            resolved.streams.map { it.kind }.toSet() != previous.tracks.map { it.kind }.toSet()) mismatch()
        val parsed = CacheSnapshot.from(resolved)
        val byKind = parsed.tracks.associateBy { it.kind }
        return parsed.copy(tracks = previous.tracks.map { old ->
            val fresh = byKind[old.kind] ?: mismatch()
            if (fresh.parts.size != old.parts.size) mismatch()
            fresh
        })
    }

    private fun verifyPart(previous: CacheSession, candidate: CacheSnapshot, trackIndex: Int, partIndex: Int,
        cached: ByteArray, remote: ByteArray) {
        val track = candidate.tracks[trackIndex]
        val part = track.parts[partIndex]
        val old = previous.snapshot.tracks[trackIndex].parts[partIndex]
        val spans = engine.cache.getCachedSpans(previous.key(trackIndex, partIndex)).toList()
        val cachedEnd = spans.lastOrNull()?.let { Math.addExact(it.position, it.length) } ?: 0L
        var bound = false
        var source: DataSource? = null
        fun open(position: Long, length: Long) {
            checkCanceled()
            val expected = CacheResponse(if (bound) part.length else old.length, part.etag, part.lastModified,
                part.rangeSupported)
            val current = engine.upstream(CacheUpstreamRequest(candidate.platform, track.headers, expected) { response ->
                checkCanceled()
                if (old.length >= 0L && old.length != response.length ||
                    response.length >= 0L && response.length < cachedEnd ||
                    bound && (part.length >= 0L && part.length != response.length ||
                        part.etag != response.etag ||
                        part.etag == null && part.lastModified != response.lastModified)) throw IOException(CACHE_CHANGED)
                if (response.length >= 0L) part.length = response.length
                part.etag = response.etag
                part.lastModified = response.lastModified
                part.rangeSupported = response.rangeSupported
                bound = true
            })
            source = current
            engine.attachRefresh(this, current)
            current.open(DataSpec.Builder().setUri(part.url).setPosition(position).setLength(length).build())
            if (!bound) mismatch()
        }
        fun close() {
            val current = source ?: return
            source = null
            engine.detachRefresh(this, current)
            current.close()
        }
        try {
            if (spans.isEmpty()) {
                open(0L, 1L)
                readRemote(checkNotNull(source), remote, 1)
                return
            }
            var index = 0
            while (index < spans.size) {
                var until = index + 1
                var end = Math.addExact(spans[index].position, spans[index].length)
                while (until < spans.size && spans[until].position == end) {
                    end = Math.addExact(end, spans[until].length)
                    until++
                }
                open(spans[index].position, end - spans[index].position)
                // Validators identify a representation only at the same URL, never across refreshed CDN URLs.
                if (old.url == part.url && old.length >= 0L && part.length == old.length &&
                    (old.etag != null && part.etag == old.etag ||
                        old.etag == null && part.etag == null && old.lastModified != null && part.lastModified == old.lastModified)) return
                if (part.rangeSupported == false) until = spans.size
                var position = spans[index].position
                while (index < until) {
                    val span = spans[index]
                    var gap = span.position - position
                    while (gap > 0L) {
                        val count = min(gap, remote.size.toLong()).toInt()
                        readRemote(checkNotNull(source), remote, count)
                        gap -= count
                    }
                    compareSpan(span, checkNotNull(source), cached, remote)
                    position = Math.addExact(span.position, span.length)
                    index++
                }
                close()
            }
        } finally { close() }
    }

    private fun compareSpan(span: CacheSpan, source: DataSource, cached: ByteArray, remote: ByteArray) {
        FileInputStream(span.file ?: throw IOException(BAD_CACHE)).use { input ->
            var remaining = span.length
            while (remaining > 0L) {
                checkCanceled()
                val count = min(remaining, cached.size.toLong()).toInt()
                var offset = 0
                while (offset < count) {
                    val read = input.read(cached, offset, count - offset)
                    if (read <= 0) mismatch()
                    offset += read
                }
                readRemote(source, remote, count)
                for (index in 0 until count) if (cached[index] != remote[index]) mismatch()
                remaining -= count
            }
        }
    }

    private fun readRemote(source: DataSource, buffer: ByteArray, count: Int) {
        var offset = 0
        while (offset < count) {
            checkCanceled()
            val read = source.read(buffer, offset, count - offset)
            if (read <= 0) mismatch()
            offset += read
        }
        checkCanceled()
    }
}

internal class CacheJob(private val session: CacheSession, private val onProgress: (Long, Long, Boolean) -> Unit) {
    @Volatile internal var canceled = false
    @Volatile internal var running = false

    fun cancel() {
        canceled = true
        session.stop(this)
    }

    fun run() {
        session.begin(this)
        val buffer = ByteArray(64 * 1024)
        try {
            report()
            while (!session.complete()) {
                var advanced = false
                session.snapshot.tracks.indices.forEach { track ->
                    checkCanceled()
                    val (part, offset) = session.nextHole(track) ?: return@forEach
                    val known = session.length(track, part)
                    val window = if (session.playable()) CACHE_WINDOW else CACHE_CHUNK
                    val count = if (known < 0L) window else min(window, known - offset)
                    val source = session.dataSource(track, part)
                    var filled = 0L
                    try {
                        source.open(session.spec(track, part, offset, count))
                        var sinceProgress = 0L
                        while (true) {
                            checkCanceled()
                            val read = source.read(buffer, 0, buffer.size)
                            if (read < 0) break
                            sinceProgress += read
                            filled += read
                            if (sinceProgress >= CACHE_CHUNK) { report(); sinceProgress = 0L }
                        }
                    } finally { source.close() }
                    val actual = session.length(track, part)
                    val expected = if (actual < 0L) count else min(count, actual - offset)
                    if (filled != expected) throw IOException(BAD_CACHE)
                    advanced = true
                    report()
                }
                if (!advanced && !session.complete()) throw IOException(BAD_CACHE)
            }
            checkCanceled()
            report()
        } catch (error: Exception) {
            if (canceled) throw BridgeException("downloadCanceled", CACHE_PAUSED)
            val message = if (error is IOException && error.message in setOf(CACHE_CHANGED, CACHE_PAUSED)) error.message!!
                else "Unduhan terhenti. Byte yang sudah diunduh tetap tersimpan; ketuk Lanjutkan untuk mencoba lagi."
            throw BridgeException("downloadFailed", message)
        } finally {
            try { session.stop(this) } finally {
                running = false
                report()
            }
        }
    }

    private fun checkCanceled() { if (canceled) throw BridgeException("downloadCanceled", CACHE_PAUSED) }
    private fun report() = onProgress(session.bytes(), session.total(), session.playable())
}

internal class NetworkGate {
    private var generation = 0L
    private var enabled = false
    private val readers = LinkedHashMap<Any, () -> Unit>()
    @Synchronized fun enable() { generation++; enabled = true }
    @Synchronized fun register(owner: Any, cancel: () -> Unit): Long {
        if (!enabled) throw IOException(CACHE_PAUSED)
        readers[owner] = cancel
        return generation
    }
    @Synchronized fun check(lease: Long) { if (!enabled || lease != generation) throw IOException(CACHE_PAUSED) }
    @Synchronized fun unregister(owner: Any) { readers.remove(owner) }
    @Synchronized
    fun detach(): List<() -> Unit> {
        enabled = false
        generation++
        return readers.values.toList().also { readers.clear() }
    }
    fun disable() {
        val cancel = detach()
        cancel.forEach { runCatching { it() } }
    }
}

private class GatedDataSource(private val gate: NetworkGate, private val factory: () -> DataSource) : DataSource {
    private var lease = -1L
    @Volatile private var upstream: DataSource? = null
    private val listeners = ArrayList<TransferListener>()
    override fun addTransferListener(listener: TransferListener) { listeners.add(listener) }
    override fun getUri(): Uri? = upstream?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream?.responseHeaders ?: emptyMap()
    override fun open(dataSpec: DataSpec): Long {
        lease = gate.register(this) {
            val current = upstream
            if (current is CancelableUpstream) current.cancel() else current?.close()
        }
        return try {
            gate.check(lease)
            val current = factory().also { upstream = it }
            listeners.forEach { current.addTransferListener(it) }
            gate.check(lease)
            current.open(dataSpec)
        } catch (error: Exception) { close(); throw error }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        gate.check(lease)
        val count = checkNotNull(upstream).read(buffer, offset, length)
        gate.check(lease)
        return count
    }
    fun usable(): Boolean = try { gate.check(lease); true } catch (_: IOException) { false }
    override fun close() { gate.unregister(this); upstream?.close(); upstream = null }
}

/** A server returning HTTP 200 keeps one body open across bounded cache windows. */
private class CacheWindowPool(
    private val sequential: () -> Boolean,
    private val factory: () -> GatedDataSource,
) {
    private class Connection(val source: GatedDataSource, var position: Long, var available: Long) {
        var inUse = false
    }
    private var retained: Connection? = null

    fun close() {
        val active = synchronized(this) { retained.also { retained = null } }
        if (active != null) synchronized(active) { active.source.close() }
    }

    fun window(): DataSource = object : DataSource {
        private var connection: Connection? = null
        private var remaining = 0L
        private val listeners = ArrayList<TransferListener>()
        override fun addTransferListener(listener: TransferListener) { listeners.add(listener) }
        override fun getUri(): Uri? = connection?.source?.uri
        override fun getResponseHeaders(): Map<String, List<String>> =
            connection?.source?.responseHeaders ?: emptyMap()

        override fun open(dataSpec: DataSpec): Long = synchronized(this@CacheWindowPool) {
            var next = retained
            if (next != null && (next.position != dataSpec.position || !next.source.usable())) {
                if (next.inUse) throw IOException(CACHE_PAUSED)
                next.source.close()
                retained = null
                next = null
            }
            if (next?.inUse == true) throw IOException(CACHE_PAUSED)
            if (next == null) {
                val source = factory()
                listeners.forEach { source.addTransferListener(it) }
                val available = try { source.open(dataSpec) } catch (error: Exception) {
                    source.close()
                    throw error
                }
                next = Connection(source, dataSpec.position, available)
                if (sequential()) retained = next
            }
            next.inUse = true
            connection = next
            remaining = when {
                dataSpec.length < 0L -> next.available
                next.available < 0L -> dataSpec.length
                else -> min(dataSpec.length, next.available)
            }
            remaining
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val active = connection ?: throw IOException(CACHE_PAUSED)
            return synchronized(active) {
                if (remaining == 0L) return@synchronized C.RESULT_END_OF_INPUT
                try {
                    val count = active.source.read(buffer, offset,
                        if (remaining < 0L) length else min(length.toLong(), remaining).toInt())
                    if (count < 0) {
                        active.available = 0L
                        remaining = 0L
                    } else {
                        active.position += count
                        if (active.available >= 0L) active.available -= count
                        if (remaining >= 0L) remaining -= count
                    }
                    count
                } catch (error: Exception) {
                    active.source.close()
                    synchronized(this@CacheWindowPool) {
                        if (retained === active) retained = null
                    }
                    throw error
                }
            }
        }

        override fun close() {
            val active = connection ?: return
            synchronized(active) {
                val shouldClose = synchronized(this@CacheWindowPool) {
                    if (connection !== active) return
                    connection = null
                    active.inUse = false
                    (retained !== active || active.available == 0L || !active.source.usable()).also {
                        if (it && retained === active) retained = null
                    }
                }
                if (shouldClose) active.source.close()
            }
        }
    }
}

/** Each cache hole is at most one chunk, so a player never waits for an entire video track. */
internal class CacheStreamDataSource(private val session: CacheSession, private val trackIndex: Int) : DataSource {
    private val listeners = ArrayList<TransferListener>()
    private var source: DataSource? = null
    private var uri: Uri? = null
    private var partIndex = 0
    private var partOffset = 0L
    private var remaining = -1L
    private var chunkRemaining = 0L

    override fun addTransferListener(listener: TransferListener) { listeners.add(listener) }
    override fun getUri(): Uri? = uri
    override fun open(dataSpec: DataSpec): Long {
        session.readerOpened(this)
        uri = dataSpec.uri
        remaining = dataSpec.length
        partIndex = 0
        partOffset = dataSpec.position
        val parts = session.snapshot.tracks[trackIndex].parts
        while (partIndex < parts.size) {
            var length = session.length(trackIndex, partIndex)
            if (length < 0L && partOffset > 0L) {
                session.awaitSequential(trackIndex, partIndex, partOffset, metadataOnly = true)
                length = session.length(trackIndex, partIndex)
                if (length < 0L) {
                    val probe = session.dataSource(trackIndex, partIndex)
                    try {
                        probe.open(session.spec(trackIndex, partIndex, 0L, 1L))
                        probe.read(ByteArray(1), 0, 1)
                    } finally { probe.close() }
                    length = session.length(trackIndex, partIndex)
                }
                if (length < 0L) throw IOException("Panjang bagian video belum diketahui. Lanjutkan unduhan sebelum melompat ke bagian ini.")
            }
            if (length < 0L || partOffset < length) break
            partOffset -= length
            partIndex++
        }
        if (partIndex == parts.size && partOffset != 0L) throw IOException("Posisi video di luar unduhan.")
        val lengths = parts.indices.map { session.length(trackIndex, it) }
        val available = if (lengths.all { it >= 0L }) lengths.fold(0L, Math::addExact) - dataSpec.position else -1L
        if (remaining < 0L) remaining = available
        else if (available >= 0L) remaining = min(remaining, available)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        session.checkPresent()
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val parts = session.snapshot.tracks[trackIndex].parts
        while (partIndex < parts.size) {
            val known = session.length(trackIndex, partIndex)
            if (known >= 0L && partOffset >= known) {
                closeChunk()
                partIndex++
                partOffset = 0L
                continue
            }
            if (source == null) {
                session.awaitSequential(trackIndex, partIndex, partOffset)
                chunkRemaining = if (session.playable()) CACHE_WINDOW else CACHE_CHUNK
                if (known >= 0L) chunkRemaining = min(chunkRemaining, known - partOffset)
                if (remaining >= 0L) chunkRemaining = min(chunkRemaining, remaining)
                val next = session.dataSource(trackIndex, partIndex)
                source = next
                listeners.forEach { next.addTransferListener(it) }
                try { next.open(session.spec(trackIndex, partIndex, partOffset, chunkRemaining)) }
                catch (error: Exception) { closeChunk(); throw error }
            }
            val count = checkNotNull(source).read(buffer, offset, min(length.toLong(), chunkRemaining).toInt())
            if (count < 0) {
                closeChunk()
                val actual = session.length(trackIndex, partIndex)
                if (actual >= 0L && partOffset == actual) { partIndex++; partOffset = 0L; continue }
                throw IOException(BAD_CACHE)
            }
            partOffset += count
            if (remaining >= 0L) remaining -= count
            chunkRemaining -= count
            if (chunkRemaining == 0L || remaining == 0L) closeChunk()
            return count
        }
        if (remaining > 0L) throw IOException(BAD_CACHE)
        return C.RESULT_END_OF_INPUT
    }

    private fun closeChunk() { val current = source; source = null; current?.close() }
    override fun close() { try { closeChunk() } finally { uri = null; session.readerClosed(this) } }
}

/** Fsync and commit before reporting progress; cancellation also commits the current partial span. */
private class DurableCacheSink(
    private val cache: SimpleCache,
    private val opened: (DurableCacheSink) -> Unit,
    private val closed: (DurableCacheSink) -> Unit,
    private val beforeWrite: () -> Unit,
    private val committed: () -> Unit,
) : DataSink {
    private var spec: DataSpec? = null
    private var output: FileOutputStream? = null
    private var file: File? = null
    private var position = 0L
    private var written = 0L

    @Synchronized override fun open(dataSpec: DataSpec) {
        spec = dataSpec
        position = dataSpec.position
        written = 0L
        opened(this)
    }

    @Synchronized override fun write(buffer: ByteArray, offset: Int, length: Int) {
        beforeWrite()
        val request = spec ?: throw IOException(CACHE_PAUSED)
        var offsetInBuffer = offset
        var remaining = length
        while (remaining > 0) {
            if (output == null) {
                val available = request.length - (position - request.position)
                file = cache.startFile(checkNotNull(request.key), position, min(CACHE_CHUNK, available))
                output = FileOutputStream(checkNotNull(file))
            }
            val count = min(remaining.toLong(), CACHE_CHUNK - written).toInt()
            checkNotNull(output).write(buffer, offsetInBuffer, count)
            written += count
            remaining -= count
            offsetInBuffer += count
            if (written == CACHE_CHUNK) checkpoint()
        }
    }

    @Synchronized fun checkpoint() {
        val stream = output ?: return
        val target = checkNotNull(file)
        try { stream.fd.sync() } finally { stream.close(); output = null; file = null }
        cache.commitFile(target, written)
        committed()
        position += written
        written = 0L
    }

    @Synchronized override fun close() {
        try { checkpoint() } finally { spec = null; closed(this) }
    }
}
