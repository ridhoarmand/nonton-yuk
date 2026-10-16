package com.nonton.yuks

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.flutter.plugin.common.EventChannel
import java.io.File
import java.io.FileOutputStream

private fun Map<String, Any?>.text(key: String): String? = this[key] as? String

private fun Any?.asLong(): Long = when (this) {
    is Number -> toLong()
    is String -> toLongOrNull() ?: 0L
    else -> 0L
}

/**
 * The download queue: FIFO, one transfer at a time. Owns the entry list, its JSON store and the
 * progress EventChannel; [DownloadService] only keeps the process alive and shows the notification.
 * Every mutation of an entry happens under [lock] so channel handlers on the main thread never
 * observe a half-written entry, while file and network work stays outside it.
 */
object DownloadManager {
    const val STATE_QUEUED = "queued"
    const val STATE_RUNNING = "running"
    const val STATE_DONE = "done"
    const val STATE_FAILED = "failed"
    const val STATE_CANCELED = "canceled"
    const val STATE_PAUSED = "paused"
    const val STATE_INTERRUPTED = "interrupted"

    private const val TAG = "NontoninDownloads"
    private const val INTERRUPTED = "Unduhan terputus karena aplikasi ditutup. Ulangi unduhan ini."
    private const val FAILED = "Unduhan gagal. Periksa koneksi internet lalu coba lagi."
    private const val INVALID = "Unduhan tidak valid."
    private const val VIDEO_CHANGED = "Video pada tautan ini sudah berbeda. Hapus lalu tambahkan ulang."
    private const val NO_VIDEO = "Berkas hasil unduhan tidak berisi video yang dapat diputar."

    private const val PHASE_DOWNLOAD = "download"
    private const val PHASE_VIDEO = "video"
    private const val PHASE_AUDIO = "audio"
    private const val PHASE_MERGE = "merge"

    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{1,128}")
    private val TASK_KEY = Regex("[A-Za-z0-9_-]{1,140}")

    private val entries = mutableListOf<MutableMap<String, Any?>>()
    private val queue = ArrayDeque<String>()
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())

    /** The transfer of one run; its identity decides whether that run may still publish. */
    private class Run(val id: String) {
        @Volatile
        var transfer: Transfer? = null

        @Volatile
        var canceled = false

        @Volatile
        var paused = false

        @Volatile
        var deleted = false

        var startMs = 0L
        var lastEmit = 0L
    }

    private val runs = HashMap<String, Run>()

    @Volatile
    private var sink: EventChannel.EventSink? = null

    private var app: Context? = null
    private var loaded = false
    private var worker: Thread? = null

    fun attach(events: EventChannel.EventSink?) {
        sink = events
    }

    /** Loads the store once, repairs dead tasks and adopts previously downloaded files. */
    fun init(context: Context) {
        synchronized(lock) {
            if (loaded) return
            loaded = true
            app = context.applicationContext
            entries += DownloadStore.load(context)
            repairInterrupted()
        }
        // Verifying the videos on disk touches media files, so it stays outside the lock.
        adoptOrphans(context)
        persist(context)
    }

    fun list(context: Context): List<Map<String, Any?>> {
        init(context)
        return synchronized(lock) { entries.map { HashMap<String, Any?>(it) } }
    }

    /** Metadata only: the resolved parts and headers never leave the native side. */
    fun probe(url: String, platform: String): Map<String, Any?> {
        val resolved = ApiClient.resolve(url, platform)
        return mapOf(
            "id" to "${resolved.platform}_${resolved.videoId}",
            "title" to resolved.title,
            "uploader" to resolved.uploader,
            "platform" to resolved.platform,
            "durationMs" to resolved.durationMs,
            "thumbnailUrl" to resolved.thumbnailUrl,
            "approxBytes" to resolved.approxBytes,
            "height" to resolved.height,
            "heights" to resolved.heights,
        )
    }

    fun enqueue(context: Context, arguments: Map<*, *>): String {
        init(context)
        val platform = (arguments["platform"] as? String)?.trim().orEmpty()
        val url = (arguments["url"] as? String)?.trim().orEmpty()
        if (url.isEmpty()) throw BridgeException("invalidArguments", INVALID)
        ApiClient.requireSourceUrl(platform, url)
        val rawId = (arguments["id"] as? String)?.trim().orEmpty()
        val videoId = rawId.removePrefix("${platform}_")
        if (!VIDEO_ID.matches(videoId)) throw BridgeException("invalidArguments", INVALID)
        val key = "${platform}_$videoId"
        check(TASK_KEY.matches(key)) { "kunci unduhan tidak aman" }
        val entry = newEntry(
            id = key,
            videoId = videoId,
            title = (arguments["title"] as? String)?.takeIf { it.isNotBlank() } ?: key,
            platform = platform,
            uploader = arguments["uploader"] as? String,
            durationMs = arguments["durationMs"].asLong(),
            thumbnailUrl = arguments["thumbnailUrl"] as? String,
            approxBytes = arguments["approxBytes"].asLong(),
            sourceUrl = url,
            height = (arguments["height"].asLong()).coerceIn(0L, 8192L).toInt(),
        )
        val canonical = ApiClient.canonicalSource(platform, url)
        synchronized(lock) {
            val old = entries.firstOrNull { it["id"] == key }
                ?: entries.firstOrNull {
                    it.text("platform") == platform &&
                        (it.text("canonicalUrl") ?: it.text("sourceUrl")) == canonical &&
                        it.text("state") in ACTIVE_OR_DONE
                }
            val oldTitle = old?.text("title") ?: key
            when (old?.text("state")) {
                STATE_QUEUED, STATE_RUNNING -> throw duplicate(oldTitle, "sedang diunduh")
                STATE_PAUSED -> throw duplicate(oldTitle, "dijeda; lanjutkan dari daftar unduhan")
                STATE_DONE -> throw duplicate(oldTitle, "sudah ada di pustaka")
            }
            if (old != null) {
                entries.remove(old)
                queue.remove(old.text("id"))
            }
            entries += entry
            queue.addLast(key)
        }
        publish(context, key, force = true)
        DownloadService.start(context)
        return key
    }

    private fun duplicate(title: String, state: String) =
        BridgeException("duplicateDownload", "$title $state. Buka item itu untuk menonton.")

    fun cancel(context: Context, id: String) {
        init(context)
        val run = synchronized(lock) {
            val entry = entries.find { it["id"] == id } ?: return
            queue.remove(id)
            val active = when (entry.text("state")) {
                STATE_RUNNING -> {
                    entry["state"] = STATE_CANCELED
                    runs[id]
                }
                STATE_QUEUED, STATE_PAUSED -> {
                    entry["state"] = STATE_CANCELED
                    // Nothing is writing anymore, so its leftovers go now instead of after a run.
                    deletePartials(context, id)
                    runs[id]
                }
                else -> return
            }
            entry["error"] = null
            entry["phase"] = null
            entry["progress"] = 0.0
            entry["etaSeconds"] = 0
            entry["bytes"] = 0L // the partials are already gone on disk
            active
        }
        // A running transfer keeps its own partials until it has stopped writing them.
        stop(run)
        publish(context, id, force = true)
    }

    /** Queued items leave the queue; running transfers stop but keep partials, phase and bytes. */
    fun pause(context: Context, id: String) {
        init(context)
        val run = synchronized(lock) {
            val entry = entries.find { it["id"] == id } ?: return
            when (entry.text("state")) {
                STATE_QUEUED -> {
                    entry["state"] = STATE_PAUSED
                    entry["error"] = null
                    queue.remove(id)
                    null
                }
                STATE_RUNNING -> {
                    entry["state"] = STATE_PAUSED
                    entry["error"] = null
                    runs[id]?.also { it.paused = true }
                }
                else -> return
            }
        }
        stop(run)
        publish(context, id, force = true)
    }

    /** Paused items rejoin the tail of the queue; partials are picked up by the next run. */
    fun resume(context: Context, id: String) {
        init(context)
        synchronized(lock) {
            val entry = entries.find { it["id"] == id } ?: return
            if (entry.text("state") != STATE_PAUSED) return
            entry["state"] = STATE_QUEUED
            entry["error"] = null
            queue.addLast(id)
        }
        publish(context, id, force = true)
        DownloadService.start(context)
        pump()
    }

    /** Idempotent: removing an unknown entry or a missing file is not an error. */
    fun delete(context: Context, id: String) {
        init(context)
        val run = synchronized(lock) {
            val entry = entries.find { it["id"] == id }
            queue.remove(id)
            if (entry != null) entries.remove(entry)
            val active = runs[id]
            if (active != null) {
                // The worker owns those paths until it stops, so it removes them itself.
                active.deleted = true
            } else {
                deleteFiles(context, id)
                File(Thumbnails.dir(context), "$id.jpg").delete()
            }
            active
        }
        stop(run)
        persist(context)
    }

    /** Marks in-flight work interrupted when the foreground service times out; does not resume it. */
    fun timeout() {
        val doomed = synchronized(lock) {
            entries.forEach { entry ->
                if (entry.text("state") == STATE_RUNNING) {
                    entry["state"] = STATE_INTERRUPTED
                    // Bytes, progress and partials stay: a retry or resume picks them up.
                    entry["phase"] = null
                    entry["etaSeconds"] = 0
                    entry["error"] = INTERRUPTED
                }
            }
            runs.values.toList()
        }
        doomed.forEach(::stop)
        app?.let { persist(it) }
    }

    /** Starts the worker thread when it is not running. */
    fun pump() {
        synchronized(lock) {
            if (worker?.isAlive == true) return
            val thread = Thread { work() }
            thread.isDaemon = true
            worker = thread
            thread.start()
        }
    }

    /** Snapshot for the foreground notification; null when nothing is pending. */
    fun active(): Map<String, Any?>? = synchronized(lock) {
        val entry = entries.find { it.text("state") == STATE_RUNNING }
            ?: entries.find { it.text("state") == STATE_QUEUED }
        entry?.let { HashMap<String, Any?>(it) }
    }

    private fun stop(run: Run?) {
        run ?: return
        run.canceled = true
        run.transfer?.cancel()
    }

    private fun work() {
        val context = app ?: return
        while (true) {
            val id = synchronized(lock) {
                val next = queue.removeFirstOrNull()
                if (next == null) worker = null // atomic with the queue check so pump() can restart
                next
            } ?: break
            run(context, id)
        }
        // shortcut: an enqueue landing between the check and stopService loses just the foreground
        // flag, the download itself still finishes; upgrade to a lock-free handshake if that bites.
        if (synchronized(lock) { worker == null && queue.isEmpty() }) DownloadService.stop(context)
    }

    private fun run(context: Context, id: String) {
        val run = Run(id)
        val entry = synchronized(lock) {
            val current = entries.find { it["id"] == id } ?: return
            if (current.text("state") != STATE_QUEUED) return
            current["state"] = STATE_RUNNING
            // Bytes/progress survive: a resume continues where the partials left off.
            current["bytes"] = partialBytes(context, id)
            if ((current["bytes"] as? Number)?.toLong() == 0L) current["progress"] = 0.0
            current["etaSeconds"] = 0
            current["error"] = null
            run.startMs = System.currentTimeMillis()
            runs[id] = run
            current
        }
        publish(context, id, force = true)
        var committed = false
        try {
            val platform = entry.text("platform").orEmpty()
            val sourceUrl = entry.text("sourceUrl").orEmpty()
            // Resolved again here: a queued item may wait long enough for the first answer to expire.
            val requestedHeight = (entry["height"] as? Number)?.toInt() ?: 0
            val resolved = ApiClient.resolve(sourceUrl, platform, requestedHeight)
            if (resolved.videoId != entry.text("videoId")) {
                throw BridgeException("videoChanged", VIDEO_CHANGED)
            }
            val total = resolved.approxBytes
            val muxed = resolved.streams.firstOrNull { it.kind == "muxed" }
            var produced: File
            if (muxed != null) {
                produced = part(context, id, "download")
                transfer(context, run, platform, muxed, produced, PHASE_DOWNLOAD, 0L, total, PHASE_DOWNLOAD)
                if (!MediaTransfer.verify(produced, requireAudio = false)) {
                    throw BridgeException("unsupportedMedia", NO_VIDEO)
                }
            } else {
                val video = resolved.streams.first { it.kind == "video" }
                val audio = resolved.streams.first { it.kind == "audio" }
                val videoPart = part(context, id, "video")
                transfer(context, run, platform, video, videoPart, PHASE_VIDEO, 0L, total, "video")
                val audioPart = part(context, id, "audio")
                transfer(context, run, platform, audio, audioPart, PHASE_AUDIO, videoPart.length(), total, "audio")
                produced = part(context, id, "merge")
                phase(id, run, PHASE_MERGE)
                MediaTransfer.mux(videoPart, audioPart, produced) { run.canceled || run.paused || run.deleted }
                if (!MediaTransfer.verify(produced, requireAudio = true)) {
                    throw BridgeException("unsupportedMedia", NO_VIDEO)
                }
            }
            val target = File(videos(context), "$id.mp4")
            if (!produced.renameTo(target)) throw BridgeException("downloadFailed", FAILED)
            // The rename raced a cancel or a delete: drop the file we just placed, never publish.
            if (!writable(id, run)) {
                target.delete()
                return
            }
            val thumbnail = Thumbnails.cache(context, id, target, 0L)
            val done = synchronized(lock) {
                val current = entries.find { it["id"] == id }
                if (runs[id] !== run || current == null ||
                    current.text("state") != STATE_RUNNING
                ) {
                    return@synchronized false
                }
                current["state"] = STATE_DONE
                current["path"] = target.absolutePath
                current["bytes"] = target.length()
                current["progress"] = 1.0
                current["etaSeconds"] = 0
                current["phase"] = null
                current["error"] = null
                current["thumbPath"] = thumbnail
                true
            }
            if (!done) {
                // Canceled or deleted between the check and the commit: the file is not ours.
                target.delete()
                return
            }
            committed = true
            publish(context, id, force = true)
        } catch (error: Exception) {
            if (run.paused) {
                // pause() already settled state, partials, phase and bytes; just announce it.
                publish(context, id, force = true)
            } else {
                Log.w(TAG, "download $id failed", error)
                val message = (error as? BridgeException)?.message ?: FAILED
                var failed = false
                synchronized(lock) {
                    val current = entries.find { it["id"] == id }
                    if (runs[id] === run && current != null &&
                        current.text("state") == STATE_RUNNING
                    ) {
                        current["state"] = STATE_FAILED
                        current["error"] = message
                        current["phase"] = null
                        current["etaSeconds"] = 0
                        failed = true
                    }
                }
                if (failed) publish(context, id, force = true)
            }
        } finally {
            // Serialized with the next run's registration: the replacement must not create these
            // paths while their leftovers are being unlinked.
            synchronized(lock) {
                if (runs[id] === run) {
                    runs.remove(id)
                    if (run.deleted) {
                        deleteFiles(context, id)
                        File(Thumbnails.dir(context), "$id.jpg").delete()
                    } else {
                        // Only terminal states drop partials; paused/interrupted keep them.
                        val state = entries.find { it["id"] == id }?.text("state")
                        if (state == STATE_FAILED || state == STATE_CANCELED) {
                            deletePartials(context, id)
                        }
                    }
                }
            }
        }
    }

    private fun transfer(
        context: Context,
        run: Run,
        platform: String,
        stream: Stream,
        target: File,
        phase: String,
        baseline: Long,
        total: Long,
        kind: String,
    ) {
        val resume = synchronized(lock) {
            val current = entries.find { it["id"] == run.id }
            Triple(
                target.length(),
                current?.text("resumeKind") == kind,
                current,
            )
        }
        val transfer = Transfer(
            platform,
            stream.parts,
            stream.headers,
            target,
            onProgress = { read -> onBytes(context, run, phase, baseline + read, total) },
            onTotal = { partTotal -> onTotal(context, run, baseline, partTotal) },
            onRestart = { onRestart(context, run, baseline) },
            resumeOffset = resume.first,
            ifRange = if (resume.second) {
                (resume.third?.get("resumeEtag") as? String)
                    ?: (resume.third?.get("resumeLastModified") as? String)
            } else {
                null
            },
        )
        run.transfer = transfer
        try {
            transfer.run()
        } finally {
            run.transfer = null
            synchronized(lock) {
                entries.find { it["id"] == run.id }?.let { current ->
                    current["resumeKind"] = kind
                    current["resumeOffset"] = target.length()
                    current["resumeEtag"] = transfer.lastEtag
                    current["resumeLastModified"] = transfer.lastModified
                }
            }
        }
    }

    /** Actual response bytes win over the estimate; unknown totals stay indeterminate. */
    private fun onTotal(context: Context, run: Run, baseline: Long, partTotal: Long) {
        if (partTotal <= 0L) return
        val copy = synchronized(lock) {
            val entry = entries.find { it["id"] == run.id } ?: return
            if (runs[run.id] !== run || entry.text("state") != STATE_RUNNING) return
            val known = (entry["approxBytes"] as? Number)?.toLong() ?: 0L
            entry["approxBytes"] = maxOf(known, baseline + partTotal)
            HashMap<String, Any?>(entry)
        }
        DownloadService.publish(context, copy, false)
        emit(copy)
    }

    /** The server restarted this part from zero; the row says so until bytes flow again. */
    private fun onRestart(context: Context, run: Run, baseline: Long) {
        synchronized(lock) {
            val entry = entries.find { it["id"] == run.id } ?: return
            if (runs[run.id] !== run || entry.text("state") != STATE_RUNNING) return
            entry["restarted"] = true
            entry["progress"] = 0.0
            entry["bytes"] = baseline
        }
        publish(context, run.id, force = true)
    }

    private fun onBytes(context: Context, run: Run, phase: String, bytes: Long, total: Long) {
        val now = System.currentTimeMillis()
        val copy = synchronized(lock) {
            val entry = entries.find { it["id"] == run.id } ?: return
            if (runs[run.id] !== run || entry.text("state") != STATE_RUNNING) return
            entry["phase"] = phase
            entry["bytes"] = bytes
            if (entry["restarted"] == true && bytes > 0L) entry["restarted"] = false
            // The estimate loses to reality; 100% is only ever reached by committing.
            val effective = maxOf(total, (entry["approxBytes"] as? Number)?.toLong() ?: 0L)
            if (effective > 0L) entry["progress"] = (bytes.toDouble() / effective).coerceAtMost(0.999)
            val elapsed = now - run.startMs
            entry["speedBps"] = if (elapsed > 1_000L && bytes > 0L) {
                (bytes * 1_000L / elapsed).coerceAtLeast(0L)
            } else {
                0L
            }
            entry["etaSeconds"] = if (elapsed > 3_000L && effective > bytes && bytes > 0L) {
                (((effective - bytes) * elapsed / bytes) / 1_000L).toInt().coerceAtLeast(0)
            } else {
                0
            }
            if (now - run.lastEmit < 1_000L) return
            run.lastEmit = now
            HashMap<String, Any?>(entry)
        }
        DownloadService.publish(context, copy, false)
        emit(copy)
    }

    /** True while this exact run may still commit and publish for [id]. */
    private fun writable(id: String, run: Run): Boolean = synchronized(lock) {
        runs[id] === run && !run.canceled && !run.paused && !run.deleted &&
            entries.find { it["id"] == id }?.text("state") == STATE_RUNNING
    }

    /** Bytes already on disk for [id]; the next run resumes after them. */
    private fun partialBytes(context: Context, id: String): Long =
        videos(context).listFiles { file ->
            file.isFile && file.name.startsWith("$id.") && file.name != "$id.mp4"
        }.orEmpty().sumOf { it.length() }

    private fun phase(id: String, run: Run, phase: String) {
        synchronized(lock) {
            val entry = entries.find { it["id"] == id } ?: return
            if (runs[id] !== run || entry.text("state") != STATE_RUNNING) return
            entry["phase"] = phase
        }
    }

    private fun publish(context: Context, id: String, force: Boolean) {
        val copy = synchronized(lock) {
            entries.find { it["id"] == id }?.let { HashMap<String, Any?>(it) }
        } ?: return
        if (force) persist(context)
        DownloadService.publish(context, copy, force)
        emit(copy)
    }

    private fun emit(payload: Map<String, Any?>) {
        main.post { sink?.success(payload) }
    }

    private fun persist(context: Context) {
        val copy = synchronized(lock) { entries.map { HashMap<String, Any?>(it) } }
        runCatching { DownloadStore.save(context, copy) }
            .onFailure { Log.w(TAG, "gagal menyimpan daftar unduhan", it) }
    }

    private fun repairInterrupted() {
        entries.forEach { entry ->
            if (entry.text("state") == STATE_RUNNING || entry.text("state") == STATE_QUEUED) {
                entry["state"] = STATE_INTERRUPTED
                entry["phase"] = null
                entry["progress"] = 0.0
                entry["etaSeconds"] = 0
                entry["error"] = INTERRUPTED
            }
        }
    }

    /** Videos already on disk that no entry knows about become finished downloads. */
    private fun adoptOrphans(context: Context) {
        val knownPaths = synchronized(lock) { entries.mapNotNull { it.text("path") }.toMutableSet() }
        val knownIds = synchronized(lock) { entries.mapNotNull { it.text("id") }.toMutableSet() }
        val adopted = mutableListOf<MutableMap<String, Any?>>()
        videos(context).listFiles { file -> file.isFile && file.extension.equals("mp4", true) }
            .orEmpty()
            .forEach { file ->
                if (file.absolutePath in knownPaths) return@forEach
                val id = file.nameWithoutExtension
                if (!knownIds.add(id)) return@forEach
                // A leftover that is not playable is a failed merge, not a video to offer.
                if (!MediaTransfer.verify(file, requireAudio = false)) {
                    Log.w(TAG, "membuang berkas tidak utuh: ${file.name}")
                    file.delete()
                    return@forEach
                }
                val entry = newEntry(
                    id = id,
                    videoId = null,
                    title = id,
                    platform = "unknown",
                    uploader = null,
                    durationMs = 0L,
                    thumbnailUrl = null,
                    approxBytes = 0L,
                    sourceUrl = "",
                )
                entry["state"] = STATE_DONE
                entry["path"] = file.absolutePath
                entry["bytes"] = file.length()
                entry["progress"] = 1.0
                entry["createdAtMs"] = file.lastModified()
                adopted += entry
            }
        if (adopted.isNotEmpty()) synchronized(lock) { entries += adopted }
    }

    private fun newEntry(
        id: String,
        videoId: String?,
        title: String,
        platform: String,
        uploader: String?,
        durationMs: Long,
        thumbnailUrl: String?,
        approxBytes: Long,
        sourceUrl: String,
        height: Int = 0,
    ): MutableMap<String, Any?> = mutableMapOf(
        "height" to height,
        "resumeKind" to null,
        "resumeOffset" to 0L,
        "resumeEtag" to null,
        "resumeLastModified" to null,
        "restarted" to false,
        "id" to id,
        "videoId" to videoId,
        "title" to title,
        "platform" to platform,
        "uploader" to uploader,
        "durationMs" to durationMs,
        "thumbnailUrl" to thumbnailUrl,
        "thumbPath" to null,
        "path" to null,
        "bytes" to 0L,
        "approxBytes" to approxBytes,
        "state" to STATE_QUEUED,
        "progress" to 0.0,
        "etaSeconds" to 0,
        "phase" to null,
        "error" to null,
        "sourceUrl" to sourceUrl,
        "canonicalUrl" to ApiClient.canonicalSource(platform, sourceUrl),
        "createdAtMs" to System.currentTimeMillis(),
    )

    private fun part(context: Context, id: String, kind: String): File =
        File(videos(context), "$id.$kind.part")

    /** Partial files of [id]: everything it owns except the committed `${id}.mp4`. */
    private fun deletePartials(context: Context, id: String) {
        videos(context).listFiles { file ->
            file.isFile && file.name.startsWith("$id.") && file.name != "$id.mp4"
        }.orEmpty()
            .forEach { it.delete() }
    }

    /** Every file of [id], finished or partial; used when the user deletes an entry. */
    private fun deleteFiles(context: Context, id: String) {
        videos(context).listFiles { file -> file.isFile && file.name.startsWith("$id.") }
            .orEmpty()
            .forEach { it.delete() }
    }

    private fun videos(context: Context): File = File(context.filesDir, "videos").apply { mkdirs() }

    private val ACTIVE_OR_DONE = setOf(STATE_QUEUED, STATE_RUNNING, STATE_PAUSED, STATE_DONE)
}

/** Cached video frames for the library grid. */
object Thumbnails {
    fun dir(context: Context): File = File(context.filesDir, "thumbs").apply { mkdirs() }

    /** Cached jpg for an app-private video file at [timeMs]; 0 picks ~10% of the duration. */
    fun cache(context: Context, id: String, video: File, timeMs: Long): String? {
        val target = File(dir(context), "$id.jpg")
        if (target.isFile && target.length() > 0L) return target.absolutePath
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(video.absolutePath)
            val at = if (timeMs > 0L) {
                timeMs
            } else {
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                duration / 10L
            }
            val frame = retriever.getFrameAtTime(
                at * 1_000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            ) ?: return null
            FileOutputStream(target).use { frame.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            frame.recycle()
            target.takeIf { it.length() > 0L }?.absolutePath
        } catch (error: Exception) {
            // A video the platform cannot decode still downloads fine; the grid falls back.
            Log.w("NontoninThumbs", "frame extraction failed for $id", error)
            target.delete()
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
