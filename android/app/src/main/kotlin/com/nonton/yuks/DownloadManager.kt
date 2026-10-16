package com.nonton.yuks

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import io.flutter.plugin.common.EventChannel
import java.io.File
import java.io.FileOutputStream

private fun Map<String, Any?>.text(key: String): String? = this[key] as? String
private fun Any?.asLong(): Long = (this as? Number)?.toLong() ?: 0L

/** One durable FIFO queue. Only explicit cancel/delete removes downloaded bytes. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
object DownloadManager {
    const val STATE_QUEUED = "queued"
    const val STATE_RUNNING = "running"
    const val STATE_DONE = "done"
    const val STATE_FAILED = "failed"
    const val STATE_CANCELED = "canceled"
    const val STATE_PAUSED = "paused"
    const val STATE_INTERRUPTED = "interrupted"

    private const val TAG = "NontoninDownloads"
    private const val FAILED = "Unduhan terhenti. Bagian yang sudah tersimpan tetap ada; ketuk Lanjutkan."
    private const val INVALID = "Unduhan tidak valid."
    private const val VIDEO_CHANGED = "Video pada tautan ini sudah berbeda. Unduhan tersimpan tidak diubah."
    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{1,128}")
    private val TASK_KEY = Regex("[A-Za-z0-9_-]{1,140}")
    private val entries = mutableListOf<MutableMap<String, Any?>>()
    private val queue = ArrayDeque<String>()
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private class Run(val id: String) {
        @Volatile var job: MediaCache.Job? = null
        @Volatile var refresh: MediaCache.RefreshJob? = null
        @Volatile var stopped = false
        @Volatile var deleted = false
        var startedAt = System.currentTimeMillis()
        var initialBytes = 0L
        var lastEmit = 0L
        var lastPersist = 0L
    }
    private val runs = HashMap<String, Run>()
    @Volatile private var sink: EventChannel.EventSink? = null
    private var app: Context? = null
    private var loaded = false
    private var worker: Thread? = null

    fun attach(events: EventChannel.EventSink?) { sink = events }

    fun init(context: Context) {
        synchronized(lock) {
            if (loaded) return
            app = context.applicationContext
            entries += DownloadStore.load(context)
            entries.forEach { entry ->
                val id = entry.text("id").orEmpty()
                if (!TASK_KEY.matches(id)) return@forEach
                entry["restarted"] = false
                if (entry.text("state") == STATE_CANCELED) {
                    removeFiles(context, id)
                    entry["playable"] = false
                    entry["resumable"] = false
                    entry["path"] = null
                    return@forEach
                }
                val refreshing = entry.text("refreshUrl") != null && entry.text("state") != STATE_DONE
                if (refreshing) {
                    entry["state"] = STATE_PAUSED
                    entry["phase"] = "refresh"
                    entry["etaSeconds"] = 0
                    entry["speedBps"] = 0L
                    entry["error"] = null
                    entry["resumable"] = true
                }
                if (entry.text("state") in setOf(STATE_RUNNING, STATE_QUEUED, STATE_INTERRUPTED)) {
                    entry["state"] = STATE_PAUSED
                    entry["etaSeconds"] = 0
                    entry["speedBps"] = 0L
                    entry["error"] = null
                    entry["resumable"] = true
                }
                if (MediaCache.exists(context, id)) {
                    entry["path"] = MediaCache.manifestPath(context, id)
                    entry["bytes"] = MediaCache.bytes(context, id)
                    entry["playable"] = MediaCache.isPlayable(context, id)
                    if (!refreshing && MediaCache.isComplete(context, id)) {
                        entry["state"] = STATE_DONE
                        entry["progress"] = 1.0
                        entry["error"] = null
                        entry["phase"] = null
                    } else {
                        val total = entry["approxBytes"].asLong()
                        if (total > 0L) entry["progress"] =
                            (entry["bytes"].asLong().toDouble() / total).coerceIn(0.0, 0.999)
                    }
                    entry["resumable"] = entry.text("state") != STATE_DONE &&
                        entry.text("state") != STATE_CANCELED
                } else if (legacyParts(context, id).isNotEmpty()) {
                    entry["resumable"] = true
                }
            }
            loaded = true
        }
        adoptOrphans(context)
        persist(context)
    }

    fun list(context: Context): List<Map<String, Any?>> {
        init(context)
        return synchronized(lock) { entries.map(::publicEntry) }
    }

    fun probe(url: String, platform: String, height: Int = 0): Map<String, Any?> {
        val result = ApiClient.resolve(url, platform, height)
        return mapOf(
            "id" to "${result.platform}_${result.videoId}", "title" to result.title,
            "uploader" to result.uploader, "platform" to result.platform,
            "durationMs" to result.durationMs, "thumbnailUrl" to result.thumbnailUrl,
            "approxBytes" to result.approxBytes, "height" to result.height,
            "heights" to result.heights,
        )
    }

    fun enqueue(context: Context, arguments: Map<*, *>): String {
        init(context)
        val platform = (arguments["platform"] as? String).orEmpty().trim()
        val url = (arguments["url"] as? String).orEmpty().trim()
        ApiClient.requireSourceUrl(platform, url)
        val videoId = (arguments["id"] as? String).orEmpty().removePrefix("${platform}_")
        if (!VIDEO_ID.matches(videoId)) throw BridgeException("invalidArguments", INVALID)
        val id = "${platform}_$videoId"
        val height = arguments["height"].asLong()
        if (height !in 0..8192) throw BridgeException("invalidArguments", INVALID)
        val canonical = ApiClient.canonicalSource(platform, url)
        synchronized(lock) {
            if (runs[id]?.stopped == true) {
                throw BridgeException("downloadStopping", "Unduhan masih dihentikan. Tunggu sebentar lalu coba lagi.")
            }
            val previous = entries.firstOrNull { it["id"] == id } ?: entries.firstOrNull {
                it.text("platform") == platform && it.text("canonicalUrl") == canonical &&
                    it.text("state") != STATE_CANCELED
            }
            if (previous != null && previous.text("state") != STATE_CANCELED) {
                val message = if (previous.text("state") == STATE_DONE) {
                    "Video sudah ada di koleksi."
                } else {
                    "Unduhan sudah ada. Buka antrean lalu ketuk Lanjutkan atau Tonton sekarang."
                }
                throw BridgeException("duplicateDownload", message)
            }
            if (previous != null) entries.remove(previous)
            entries += newEntry(id, videoId,
                (arguments["title"] as? String)?.takeIf { it.isNotBlank() } ?: id,
                platform, url).apply {
                this["height"] = height.toInt()
                this["uploader"] = arguments["uploader"] as? String
                this["durationMs"] = arguments["durationMs"].asLong()
                this["thumbnailUrl"] = arguments["thumbnailUrl"] as? String
                this["approxBytes"] = arguments["approxBytes"].asLong()
            }
            queue.addLast(id)
        }
        publish(context, id, true)
        DownloadService.start(context)
        return id
    }

    fun refreshLink(context: Context, id: String, url: String? = null) {
        init(context)
        synchronized(lock) {
            val index = entries.indexOfFirst { it["id"] == id }
            val entry = entries.getOrNull(index)
                ?: throw BridgeException("missingVideo", "Video tidak ada lagi di koleksi.")
            if (runs.containsKey(id)) {
                throw BridgeException("downloadStopping", "Unduhan masih dihentikan. Tunggu sebentar lalu coba lagi.")
            }
            val state = entry.text("state")
            if ((state != STATE_PAUSED && state != STATE_FAILED && state != STATE_INTERRUPTED) ||
                !VIDEO_ID.matches(entry.text("videoId").orEmpty())) {
                throw BridgeException("invalidArguments", INVALID)
            }
            val platform = entry.text("platform").orEmpty()
            val source = (url ?: entry.text("sourceUrl").orEmpty()).trim()
            ApiClient.requireSourceUrl(platform, source)
            entries[index] = HashMap(entry).apply {
                this["refreshUrl"] = source
                this["phase"] = "refresh"
                this["state"] = STATE_QUEUED
                this["resumable"] = true
                this["error"] = null
                this["etaSeconds"] = 0
                this["speedBps"] = 0L
            }
            try {
                DownloadStore.save(context, entries)
            } catch (_: Exception) {
                entries[index] = entry
                throw BridgeException("refreshFailed", "Antrean tidak dapat disimpan. Coba perbarui link lagi.")
            }
            if (!queue.contains(id)) queue.addLast(id)
        }
        publish(context, id, false)
        DownloadService.start(context)
        pump()
    }

    fun pause(context: Context, id: String) {
        init(context)
        val run = synchronized(lock) {
            val entry = entries.find { it["id"] == id } ?: return
            if (entry.text("state") !in setOf(STATE_RUNNING, STATE_QUEUED)) return
            queue.remove(id)
            entry["state"] = STATE_PAUSED
            entry["resumable"] = true
            entry["error"] = null
            entry["etaSeconds"] = 0
            entry["speedBps"] = 0L
            runs[id]?.also { it.stopped = true }
        }
        run?.job?.cancel()
        run?.refresh?.cancel()
        publish(context, id, true)
    }

    fun resume(context: Context, id: String) {
        init(context)
        synchronized(lock) {
            val entry = entries.find { it["id"] == id } ?: return
            if (entry.text("state") !in setOf(STATE_PAUSED, STATE_INTERRUPTED, STATE_FAILED)) return
            if (runs.containsKey(id)) {
                throw BridgeException("downloadStopping", "Unduhan masih dihentikan. Tunggu sebentar lalu coba lagi.")
            }
            if (entry["resumable"] != true) return
            entry["state"] = STATE_QUEUED
            entry["error"] = null
            entry["speedBps"] = 0L
            entry["etaSeconds"] = 0
            if (entry.text("refreshUrl") != null) entry["phase"] = "refresh"
            if (!queue.contains(id)) queue.addLast(id)
        }
        publish(context, id, true)
        DownloadService.start(context)
        pump()
    }

    fun cancel(context: Context, id: String) {
        init(context)
        val run = synchronized(lock) {
            val entry = entries.find { it["id"] == id } ?: return
            if (entry.text("state") == STATE_DONE) return
            queue.remove(id)
            entry["state"] = STATE_CANCELED
            entry["playable"] = false
            entry["resumable"] = false
            entry["path"] = null
            entry["bytes"] = 0L
            entry["progress"] = 0.0
            entry["etaSeconds"] = 0
            entry["speedBps"] = 0L
            entry["error"] = null
            entry.remove("refreshUrl")
            entry["phase"] = null
            runs[id]?.also { it.stopped = true }
                .also { if (it == null) removeFiles(context, id) }
        }
        run?.job?.cancel()
        run?.refresh?.cancel()
        publish(context, id, true)
    }

    fun delete(context: Context, id: String) {
        init(context)
        val run = synchronized(lock) {
            entries.removeAll { it["id"] == id }
            queue.remove(id)
            runs[id]?.also { it.deleted = true; it.stopped = true }
                .also { if (it == null) removeFiles(context, id) }
        }
        run?.job?.cancel()
        run?.refresh?.cancel()
        persist(context)
    }

    fun timeout() {
        val context = app ?: return
        val ids = synchronized(lock) {
            entries.filter { it.text("state") in setOf(STATE_RUNNING, STATE_QUEUED) }
                .mapNotNull { it.text("id") }
        }
        ids.forEach { pause(context, it) }
    }

    fun pump() {
        synchronized(lock) {
            if (worker?.isAlive == true) return
            worker = Thread { work() }.also { it.isDaemon = true; it.start() }
        }
    }

    fun active(): Map<String, Any?>? = synchronized(lock) {
        (entries.find { it.text("state") == STATE_RUNNING }
            ?: entries.find { it.text("state") == STATE_QUEUED })?.let(::publicEntry)
    }

    private fun work() {
        val context = app ?: return
        while (true) {
            val id = synchronized(lock) {
                queue.removeFirstOrNull().also { if (it == null) worker = null }
            } ?: break
            run(context, id)
        }
        if (synchronized(lock) { worker == null && queue.isEmpty() }) DownloadService.stop(context)
    }

    private fun run(context: Context, id: String) {
        val run = Run(id)
        val snapshot = synchronized(lock) {
            val entry = entries.find { it["id"] == id } ?: return
            if (entry.text("state") != STATE_QUEUED) return
            entry["state"] = STATE_RUNNING
            entry["phase"] = if (entry.text("refreshUrl") != null) "refresh" else "download"
            entry["error"] = null
            runs[id] = run
            HashMap<String, Any?>(entry)
        }
        publish(context, id, true)
        try {
            val existing = MediaCache.exists(context, id)
            val refreshUrl = snapshot.text("refreshUrl")
            var resolved: Resolved? = null
            if (refreshUrl != null) {
                if (existing) {
                    val refresh = MediaCache.newRefreshJob(context, id) { platform, height ->
                        ApiClient.resolve(refreshUrl, platform, height)
                    }
                    run.refresh = refresh
                    if (!writable(run)) { refresh.cancel(); return }
                    acceptRefresh(context, run, refresh.run())
                } else {
                    resolved = ApiClient.resolve(refreshUrl, snapshot.text("platform").orEmpty(),
                        snapshot["height"].asLong().toInt()).also {
                        if (it.videoId != snapshot.text("videoId")) {
                            throw BridgeException("videoChanged", VIDEO_CHANGED)
                        }
                    }
                }
            }
            if (!writable(run)) return
            if (recoverLegacyParts(context, run, resolved)) return
            if (!existing && legacyParts(context, id).isNotEmpty()) {
                throw BridgeException("legacyPartial", "Sisa unduhan lama disimpan, tetapi metadata untuk melanjutkannya tidak lengkap. Tidak ada berkas yang dihapus. Batalkan hanya jika ingin mengunduh ulang.")
            }
            if (!existing && resolved == null) {
                resolved = ApiClient.resolve(
                    snapshot.text("sourceUrl").orEmpty(), snapshot.text("platform").orEmpty(),
                    snapshot["height"].asLong().toInt(),
                ).also {
                    if (it.videoId != snapshot.text("videoId")) throw BridgeException("videoChanged", VIDEO_CHANGED)
                }
            }
            if (!writable(run)) return
            run.initialBytes = MediaCache.bytes(context, id)
            run.startedAt = System.currentTimeMillis()
            val job = MediaCache.newJob(context, id, resolved) { bytes, total, playable ->
                progress(context, run, bytes, total, playable)
            }
            run.job = job
            if (refreshUrl != null && resolved != null) acceptRefresh(context, run, resolved)
            synchronized(lock) {
                entries.find { it["id"] == id }?.let {
                    if (run.deleted || runs[id] !== run || it.text("state") == STATE_CANCELED) return@let
                    it["path"] = MediaCache.manifestPath(context, id)
                    it["resumable"] = true
                }
            }
            publish(context, id, true)
            if (!writable(run)) { job.cancel(); return }
            job.run()
            if (!writable(run)) return
            synchronized(lock) {
                val entry = entries.find { it["id"] == id } ?: return
                if (entry.text("state") != STATE_RUNNING || run.stopped) return
                entry["state"] = STATE_DONE
                entry["phase"] = null
                entry["bytes"] = MediaCache.bytes(context, id)
                entry["progress"] = 1.0
                entry["playable"] = true
                entry["resumable"] = false
                entry["etaSeconds"] = 0
                entry["speedBps"] = 0L
                entry["error"] = null
            }
            publish(context, id, true)
        } catch (error: Exception) {
            synchronized(lock) {
                val entry = entries.find { it["id"] == id }
                // A pause/stop owns the state; its socket exception is not a failed download.
                if (entry != null && !run.stopped && entry.text("state") == STATE_RUNNING) {
                    entry["state"] = STATE_FAILED
                    entry["resumable"] = true
                    entry["error"] = (error as? BridgeException)?.message ?: FAILED
                    entry["etaSeconds"] = 0
                    entry["speedBps"] = 0L
                }
            }
            publish(context, id, true)
        } finally {
            run.job?.cancel()
            run.refresh?.cancel()
            val cachedBytes = MediaCache.bytes(context, id)
            val cachedPlayable = MediaCache.isPlayable(context, id)
            synchronized(lock) {
                val entry = entries.find { it["id"] == id }
                if (entry != null && entry.text("state") != STATE_CANCELED && cachedBytes > 0L) {
                    entry["bytes"] = cachedBytes
                    entry["playable"] = cachedPlayable
                }
                // Keep the stopping run registered until deletion finishes to protect a replacement task.
                if (run.deleted || entry?.text("state") == STATE_CANCELED) removeFiles(context, id)
                if (runs[id] === run) runs.remove(id)
            }
            persist(context)
            if (!run.deleted) publish(context, id, false)
        }
    }

    private fun acceptRefresh(context: Context, run: Run, resolved: Resolved) {
        synchronized(lock) {
            val entry = entries.find { it["id"] == run.id } ?: return
            if (run.deleted || runs[run.id] !== run || entry.text("state") == STATE_CANCELED) return
            entry["sourceUrl"] = resolved.sourceUrl
            entry["canonicalUrl"] = ApiClient.canonicalSource(resolved.platform, resolved.sourceUrl)
            entry.remove("refreshUrl")
            entry["phase"] = if (entry.text("state") == STATE_DONE) null else "download"
            entry["etaSeconds"] = 0
            entry["speedBps"] = 0L
        }
        publish(context, run.id, true)
    }

    private fun writable(run: Run): Boolean = synchronized(lock) {
        !run.stopped && !run.deleted && runs[run.id] === run &&
            entries.find { it["id"] == run.id }?.text("state") == STATE_RUNNING
    }

    private fun progress(context: Context, run: Run, bytes: Long, total: Long, playable: Boolean) {
        val now = System.currentTimeMillis()
        val shouldEmit = synchronized(lock) {
            val entry = entries.find { it["id"] == run.id } ?: return
            if (!writable(run)) return
            val readinessChanged = entry["playable"] != playable
            entry["bytes"] = bytes
            entry["playable"] = playable
            if (total > 0L) entry["approxBytes"] = total
            val effective = if (total > 0L) total else entry["approxBytes"].asLong()
            entry["progress"] = if (effective > 0L) (bytes.toDouble() / effective).coerceIn(0.0, 0.999) else 0.0
            val elapsed = (now - run.startedAt).coerceAtLeast(1L)
            val transferred = (bytes - run.initialBytes).coerceAtLeast(0L)
            val speed = if (elapsed >= 1_000L) transferred * 1_000L / elapsed else 0L
            entry["speedBps"] = speed
            entry["etaSeconds"] = if (speed > 0L && effective > bytes) ((effective - bytes) / speed).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0
            (readinessChanged || now - run.lastEmit >= 1_000L).also { if (it) run.lastEmit = now }
        }
        if (shouldEmit) publish(context, run.id, false)
        if (now - run.lastPersist >= 5_000L) {
            run.lastPersist = now
            persist(context)
        }
    }

    /** All player sources are selected from owned entries, never caller-supplied paths or URLs. */
    fun playbackSource(context: Context, id: String): MediaSource {
        init(context)
        val entry = synchronized(lock) {
            entries.find { it["id"] == id }?.let { HashMap<String, Any?>(it) }
        } ?: throw BridgeException("missingVideo", "Video tidak ada lagi di koleksi.")
        if (entry.text("state") == STATE_CANCELED) throw BridgeException("missingVideo", "Unduhan sudah dibatalkan.")
        if (MediaCache.exists(context, id)) return MediaCache.source(context, id)
        if (entry.text("state") != STATE_DONE) throw BridgeException("notReady", "Buffer belum siap. Lanjutkan unduhan lebih dulu.")
        val path = entry.text("path") ?: throw BridgeException("missingVideo", "Berkas video tidak ditemukan.")
        val file = ownedVideo(context, path)
        val factory = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(context))
        val video = factory.createMediaSource(MediaItem.fromUri(android.net.Uri.fromFile(file)))
        return entry.text("audioPath")?.let {
            val audio = factory.createMediaSource(MediaItem.fromUri(android.net.Uri.fromFile(ownedVideo(context, it))))
            MergingMediaSource(video, audio)
        } ?: video
    }

    private fun ownedVideo(context: Context, path: String): File {
        val file = File(path).canonicalFile
        if (file.parentFile != videos(context).canonicalFile || !file.isFile || file.length() == 0L) {
            throw BridgeException("missingVideo", "Berkas video tidak ditemukan. Koleksi tidak diubah.")
        }
        return file
    }

    /** Completed old tracks can play separately: no copying, merging, or re-downloading. */
    private fun recoverLegacyParts(context: Context, run: Run, refreshed: Resolved?): Boolean {
        if (MediaCache.exists(context, run.id)) return false
        val muxed = File(videos(context), "${run.id}.download.part")
        val video = File(videos(context), "${run.id}.video.part")
        val audio = File(videos(context), "${run.id}.audio.part")
        val recoveredVideo: File
        var recoveredAudio: File? = null
        if (MediaTransfer.completeTrack(muxed, MediaFormat.MIMETYPE_VIDEO_AVC)) {
            recoveredVideo = muxed
        } else if (MediaTransfer.completeTrack(video, MediaFormat.MIMETYPE_VIDEO_AVC) &&
            MediaTransfer.completeTrack(audio, MediaFormat.MIMETYPE_AUDIO_AAC)) {
            recoveredVideo = video
            recoveredAudio = audio
        } else return false
        if (!writable(run)) return true
        val thumb = Thumbnails.cache(context, run.id, recoveredVideo, 0L)
        synchronized(lock) {
            val entry = entries.find { it["id"] == run.id } ?: return true
            if (!writable(run)) return true
            entry["state"] = STATE_DONE
            entry["path"] = recoveredVideo.absolutePath
            entry["audioPath"] = recoveredAudio?.absolutePath
            entry["bytes"] = recoveredVideo.length() + (recoveredAudio?.length() ?: 0L)
            entry["thumbPath"] = thumb
            entry["progress"] = 1.0
            entry["playable"] = true
            entry["resumable"] = false
            entry["phase"] = null
            entry["error"] = null
            if (refreshed != null) {
                entry["sourceUrl"] = refreshed.sourceUrl
                entry["canonicalUrl"] = ApiClient.canonicalSource(refreshed.platform, refreshed.sourceUrl)
                entry.remove("refreshUrl")
            }
        }
        publish(context, run.id, true)
        return true
    }

    private fun publish(context: Context, id: String, save: Boolean) {
        if (save) persist(context)
        val entry = synchronized(lock) {
            entries.find { it["id"] == id }?.let(::publicEntry)
        } ?: return
        DownloadService.publish(context, entry, true)
        main.post { sink?.success(entry) }
    }

    private fun publicEntry(entry: Map<String, Any?>): Map<String, Any?> =
        HashMap<String, Any?>(entry).apply { remove("refreshUrl") }

    private fun persist(context: Context) {
        synchronized(lock) {
            runCatching { DownloadStore.save(context, entries) }
                .onFailure { Log.w(TAG, "Daftar unduhan tidak dapat disimpan.") }
        }
    }

    private fun adoptOrphans(context: Context) {
        val known = synchronized(lock) { entries.mapNotNull { it.text("id") }.toSet() }
        videos(context).listFiles { file -> file.isFile && file.extension.equals("mp4", true) }
            .orEmpty().forEach { file ->
                val id = file.nameWithoutExtension
                if (id in known || !TASK_KEY.matches(id) || !MediaTransfer.verify(file, false)) return@forEach
                val entry = newEntry(id, null, id, "unknown", "").apply {
                    this["state"] = STATE_DONE
                    this["path"] = file.absolutePath
                    this["bytes"] = file.length()
                    this["progress"] = 1.0
                    this["playable"] = true
                    this["resumable"] = false
                    this["createdAtMs"] = file.lastModified()
                }
                synchronized(lock) { entries += entry }
            }
    }

    private fun newEntry(id: String, videoId: String?, title: String, platform: String, url: String): MutableMap<String, Any?> = mutableMapOf(
        "id" to id, "videoId" to videoId, "title" to title, "platform" to platform,
        "sourceUrl" to url, "canonicalUrl" to ApiClient.canonicalSource(platform, url),
        "createdAtMs" to System.currentTimeMillis(), "bytes" to 0L, "approxBytes" to 0L,
        "height" to 0, "durationMs" to 0L, "state" to STATE_QUEUED, "progress" to 0.0,
        "path" to null, "phase" to null, "error" to null, "etaSeconds" to 0,
        "speedBps" to 0L, "playable" to false, "resumable" to true,
    )

    private fun videos(context: Context): File = File(context.filesDir, "videos").apply { mkdirs() }
    private fun legacyParts(context: Context, id: String): List<File> =
        videos(context).listFiles { it.isFile && it.name.startsWith("$id.") && it.name.endsWith(".part") }.orEmpty().toList()

    private fun removeFiles(context: Context, id: String) {
        if (!TASK_KEY.matches(id)) return
        MediaCache.remove(context, id)
        videos(context).listFiles { it.isFile && it.name.startsWith("$id.") }.orEmpty().forEach { it.delete() }
        File(Thumbnails.dir(context), "$id.jpg").delete()
    }
}

/** Existing collection frames remain local; thumbnail failures never delete a video. */
object Thumbnails {
    fun dir(context: Context): File = File(context.filesDir, "thumbs").apply { mkdirs() }

    fun cache(context: Context, id: String, video: File, timeMs: Long): String? {
        val target = File(dir(context), "$id.jpg")
        if (target.isFile && target.length() > 0L) return target.absolutePath
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(video.absolutePath)
            val at = if (timeMs > 0L) timeMs else {
                (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 10L
            }
            val frame = retriever.getFrameAtTime(at * 1_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            FileOutputStream(target).use { frame.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            frame.recycle()
            target.takeIf { it.length() > 0L }?.absolutePath
        } catch (_: Exception) {
            target.delete()
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
