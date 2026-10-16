package com.nonton.yuks

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.nio.ByteBuffer
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.math.min

private const val TAG = "NontoninTransfer"
private const val MEDIA_PORT = 443
private const val CONNECT_TIMEOUT = 10_000
private const val READ_TIMEOUT = 20_000
private const val RESOLVE_TIMEOUT = 5_000
private const val STALL_TIMEOUT = 30_000
private const val MAX_REDIRECTS = 5
private const val READ_BUFFER = 64 * 1024
private val RANGE_TOTAL = Regex("""bytes (\\d+)-\\d+/(\\d+|\\*)""")
private const val MAX_HEADER_BYTES = 16 * 1024
private const val TRUNCATED = "Unduhan terpotong sebelum selesai. Coba lagi."
private const val REJECTED = "Server media menolak unduhan ini. Coba lagi nanti."
private const val NOT_MEDIA = "Server mengirim halaman, bukan berkas media."
private const val UNSAFE_HOST = "Alamat server media tidak aman untuk dihubungi."
private const val NETWORK = "Koneksi ke server media bermasalah. Periksa jaringan lalu coba lagi."
private const val CANCELED = "Unduhan dibatalkan."
private const val NO_VIDEO = "Berkas hasil unduhan tidak berisi video yang dapat diputar."
private const val MERGE_FAILED = "Video ini tidak dapat digabungkan di perangkat ini."
private const val TOO_LARGE = "Salah satu bagian video terlalu besar untuk digabungkan di perangkat ini."

/**
 * Streams one stream's parts into a single local file. Every hop is re-validated, DNS answers must
 * be public, and the connection goes to a checked address while TLS still verifies [platform]'s
 * hostname, so a hostile DNS answer cannot redirect the media bytes.
 */
class Transfer(
    private val platform: String,
    private val parts: List<String>,
    private val headers: Map<String, String>,
    private val target: File,
    private val onProgress: (Long) -> Unit,
    private val onTotal: (Long) -> Unit = {},
    private val onRestart: () -> Unit = {},
    resumeOffset: Long = 0L,
    ifRange: String? = null,
) {
    @Volatile
    private var canceled = false

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var plainSocket: Socket? = null

    private var received = 0L
    private var offset = resumeOffset.coerceAtLeast(0L)
    private val rangeValidator = ifRange?.takeIf { it.isNotEmpty() && !it.startsWith("W/") }
    var lastEtag: String? = null
        private set
    var lastModified: String? = null
        private set

    fun cancel() {
        canceled = true
        runCatching { plainSocket?.close() }
        runCatching { socket?.close() }
    }

    fun run() {
        // One Range header cannot resume a multi-part stream (offsets are per URL), so those
        // restart visibly; single-part streams resume from the existing partial when present.
        if (offset > 0L && (parts.size != 1 || !target.isFile)) {
            offset = 0L
            onRestart()
        }
        received = offset
        var output: java.io.OutputStream = FileOutputStream(target, offset > 0L).buffered(256 * 1024)
        try {
            parts.forEach { part ->
                if (canceled) throw BridgeException("downloadCanceled", CANCELED)
                try {
                    fetch(part, output, offset)
                } catch (_: RestartFromZero) {
                    output.close()
                    offset = 0L
                    received = 0L
                    output = FileOutputStream(target, false)
                    onRestart()
                    fetch(part, output, 0L)
                }
            }
        } finally {
            runCatching { output.close() }
        }
    }

    private object RestartFromZero : Exception()

    private fun fetch(rawUrl: String, output: java.io.OutputStream, rangeFrom: Long) {
        var uri = mediaUri(rawUrl)
        var hops = 0
        while (true) {
            if (canceled) throw BridgeException("downloadCanceled", CANCELED)
            val response = open(uri, rangeFrom.takeIf { it > 0L })
            try {
                if (response.status in 300..399) {
                    val location = response.header("location") ?: throw BridgeException("downloadFailed", REJECTED)
                    hops += 1
                    if (hops > MAX_REDIRECTS) throw BridgeException("downloadFailed", REJECTED)
                    uri = mediaUri(absolute(uri, location))
                    continue
                }
                val host = uri.host.orEmpty()
                if (rangeFrom > 0L) {
                    when (response.status) {
                        206 -> {
                            val (start, total) = parseContentRange(response.header("content-range"))
                            if (start != rangeFrom) throw BridgeException("downloadFailed", TRUNCATED)
                            Log.d(TAG, "melanjutkan bagian: host=" + host + " total=" + total)
                            onTotal(total)
                        }
                        // The server ignored the range or the bytes changed: start over visibly.
                        200, 416 -> throw RestartFromZero
                        else -> throw BridgeException("downloadFailed", REJECTED)
                    }
                } else {
                    if (response.status != 200) throw BridgeException("downloadFailed", REJECTED)
                    val length = response.header("content-length")?.toLongOrNull()?.takeIf { it >= 0L } ?: 0L
                    Log.d(TAG, "mengunduh bagian: host=" + host + " status=" + response.status + " adaPanjang=" + (length > 0L))
                    onTotal(length)
                }
                if (!acceptable(response.header("content-type"))) {
                    throw BridgeException("downloadFailed", NOT_MEDIA)
                }
                lastEtag = response.header("etag")?.takeIf { it.isNotEmpty() && !it.startsWith("W/") }
                lastModified = response.header("last-modified")?.takeIf { it.isNotEmpty() }
                val body = Body.of(
                    response.header("transfer-encoding"),
                    response.header("content-length"),
                    response.stream,
                )
                val buffer = ByteArray(READ_BUFFER)
                var lastByte = System.currentTimeMillis()
                while (true) {
                    if (canceled) throw BridgeException("downloadCanceled", CANCELED)
                    val read = body.read(buffer)
                    if (read < 0) break
                    if (read == 0) {
                        if (System.currentTimeMillis() - lastByte > STALL_TIMEOUT) {
                            throw BridgeException("downloadFailed", NETWORK)
                        }
                        continue
                    }
                    output.write(buffer, 0, read)
                    received += read
                    lastByte = System.currentTimeMillis()
                    onProgress(received)
                }
                if (body.truncated()) throw BridgeException("downloadFailed", TRUNCATED)
                if (System.currentTimeMillis() - lastByte > STALL_TIMEOUT) {
                    throw BridgeException("downloadFailed", NETWORK)
                }
                return
            } finally {
                response.close()
            }
        }
    }

    private fun parseContentRange(value: String?): Pair<Long, Long> {
        val match = value?.let { RANGE_TOTAL.find(it) } ?: throw BridgeException("downloadFailed", TRUNCATED)
        val start = match.groupValues[1].toLong()
        val total = match.groupValues[2].takeIf { it != "*" }?.toLong() ?: 0L
        return start to total
    }

    /** Redirect targets may be relative; resolve against the hop, then re-validate it. */
    private fun absolute(from: Uri, location: String): String = try {
        URL(URL(from.toString()), location).toString()
    } catch (_: Exception) {
        throw BridgeException("downloadFailed", REJECTED)
    }

    /** Manual redirects re-run the same checks as the first hop, including the host suffixes. */
    private fun mediaUri(raw: String): Uri {
        if (raw.isEmpty() || raw.length > 16 * 1024) throw BridgeException("downloadFailed", REJECTED)
        if (raw.any { it.code !in 0x21..0x7e }) throw BridgeException("downloadFailed", REJECTED)
        val uri = Uri.parse(raw)
        if (uri.scheme != "https" || uri.userInfo != null) throw BridgeException("downloadFailed", REJECTED)
        if (uri.port != -1 && uri.port != MEDIA_PORT) throw BridgeException("downloadFailed", REJECTED)
        val host = uri.host ?: throw BridgeException("downloadFailed", REJECTED)
        if (!Platforms.media(host, platform)) throw BridgeException("downloadFailed", REJECTED)
        return uri
    }

    private fun open(uri: Uri, rangeFrom: Long?): Response {
        val host = uri.host ?: throw BridgeException("downloadFailed", REJECTED)
        val address = resolve(host)
        val plain = Socket()
        plainSocket = plain
        val secure = try {
            plain.tcpNoDelay = true
            plain.connect(InetSocketAddress(address, MEDIA_PORT), CONNECT_TIMEOUT)
            // SNI and hostname verification keep the original hostname while the socket goes to the
            // address we already checked, so a rebinding answer cannot move the connection.
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(plain, host, MEDIA_PORT, true) as SSLSocket
            ssl.soTimeout = READ_TIMEOUT
            ssl.startHandshake()
            ssl
        } catch (error: IOException) {
            runCatching { plain.close() }
            throw BridgeException("downloadFailed", NETWORK)
        }
        socket = secure
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, secure.session)) {
            runCatching { secure.close() }
            throw BridgeException("downloadFailed", UNSAFE_HOST)
        }
        val stream = BufferedInputStream(secure.inputStream, 256 * 1024)
        return try {
            val path = uri.encodedPath.orEmpty().ifEmpty { "/" } +
                uri.encodedQuery?.let { "?$it" }.orEmpty()
            val request = StringBuilder()
                .append("GET ").append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(host).append("\r\n")
                .append("Accept-Encoding: identity\r\n")
                .append("Connection: close\r\n")
            headers.forEach { (name, value) -> request.append(name).append(": ").append(value).append("\r\n") }
            if (rangeFrom != null && rangeFrom > 0L) {
                request.append("Range: bytes=").append(rangeFrom).append("-\r\n")
                rangeValidator?.let { request.append("If-Range: ").append(it).append("\r\n") }
            }
            request.append("\r\n")
            val raw = secure.outputStream
            raw.write(request.toString().toByteArray(Charsets.ISO_8859_1))
            raw.flush()
            val (status, responseHeaders) = readHead(stream)
            Response(status, responseHeaders, stream, secure)
        } catch (error: Exception) {
            runCatching { secure.close() }
            if (error is BridgeException) throw error
            throw BridgeException("downloadFailed", NETWORK)
        }
    }

    private fun resolve(host: String): InetAddress {
        var resolved: Array<InetAddress>? = null
        val lookup = Thread {
            try {
                resolved = InetAddress.getAllByName(host)
            } catch (_: IOException) {
            }
        }
        lookup.isDaemon = true
        lookup.start()
        lookup.join(RESOLVE_TIMEOUT.toLong())
        if (lookup.isAlive) runCatching { lookup.interrupt() }
        val addresses = resolved ?: throw BridgeException("downloadFailed", NETWORK)
        addresses.forEach { address ->
            if (address.isAnyLocalAddress || address.isLoopbackAddress ||
                address.isLinkLocalAddress || address.isSiteLocalAddress ||
                address.isMulticastAddress
            ) {
                throw BridgeException("downloadFailed", UNSAFE_HOST)
            }
        }
        if (addresses.isEmpty()) throw BridgeException("downloadFailed", NETWORK)
        return addresses.first()
    }

    private fun readHead(stream: InputStream): Pair<Int, Map<String, String>> {
        val head = ByteArrayOutputStream()
        var matched = 0
        while (matched < 4) {
            val byte = stream.read()
            if (byte < 0) throw BridgeException("downloadFailed", REJECTED)
            head.write(byte)
            matched = when {
                matched == 0 && byte == '\r'.code -> 1
                matched == 1 && byte == '\n'.code -> 2
                matched == 2 && byte == '\r'.code -> 3
                matched == 3 && byte == '\n'.code -> 4
                byte == '\r'.code -> 1
                else -> 0
            }
            if (head.size() > MAX_HEADER_BYTES) throw BridgeException("downloadFailed", REJECTED)
        }
        val lines = head.toString("ISO-8859-1").split("\r\n").filter { it.isNotEmpty() }
        val status = lines.firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull()
            ?: throw BridgeException("downloadFailed", REJECTED)
        val headers = LinkedHashMap<String, String>()
        lines.drop(1).forEach { line ->
            val separator = line.indexOf(':')
            if (separator > 0) headers[line.substring(0, separator).trim().lowercase()] =
                line.substring(separator + 1).trim()
        }
        return status to headers
    }

    private fun acceptable(contentType: String?): Boolean {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (type.isEmpty()) return true
        return !(type.startsWith("text/") || type.contains("json") ||
            type.contains("html") || type.contains("xml") || type.contains("javascript"))
    }
}

private class Response(
    val status: Int,
    private val headers: Map<String, String>,
    val stream: InputStream,
    private val socket: Socket,
) : Closeable {
    fun header(name: String): String? = headers[name]

    override fun close() {
        runCatching { stream.close() }
        runCatching { socket.close() }
    }
}

/** Body framing: content-length, chunked, or up to close. A short body is a truncation. */
private abstract class Body(protected val source: InputStream) {
    abstract fun read(buffer: ByteArray): Int

    open fun truncated(): Boolean = false

    companion object {
        fun of(transferEncoding: String?, contentLength: String?, source: InputStream): Body = when {
            transferEncoding?.contains("chunked", true) == true -> Chunked(source)
            contentLength?.toLongOrNull()?.takeIf { it >= 0L } != null ->
                Length(contentLength.toLong(), source)
            else -> UntilClose(source)
        }
    }
}

private class Length(private var remaining: Long, source: InputStream) : Body(source) {
    override fun read(buffer: ByteArray): Int {
        if (remaining <= 0L) return -1
        val read = source.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
        if (read < 0) return -1
        remaining -= read
        return read
    }

    override fun truncated(): Boolean = remaining > 0L
}

private class UntilClose(source: InputStream) : Body(source) {
    override fun read(buffer: ByteArray): Int = source.read(buffer, 0, buffer.size)
}

private class Chunked(source: InputStream) : Body(source) {
    private var remaining = 0L
    private var done = false
    private var short = false

    override fun read(buffer: ByteArray): Int {
        if (done) return -1
        if (remaining == 0L) {
            val size = line().substringBefore(';').trim().toLongOrNull(16) ?: run {
                short = true
                done = true
                return -1
            }
            if (size == 0L) {
                // Trailer headers, then the closing CRLF line.
                while (line().isNotEmpty()) {
                    // nothing to keep
                }
                done = true
                return -1
            }
            remaining = size
        }
        val read = source.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
        if (read < 0) {
            short = true
            done = true
            return -1
        }
        remaining -= read
        if (remaining == 0L) line()
        return read
    }

    override fun truncated(): Boolean = short

    private fun line(): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val byte = source.read()
            if (byte < 0) break
            if (byte == '\n'.code) break
            out.write(byte)
            if (out.size() > MAX_HEADER_BYTES) break
        }
        return out.toString("ISO-8859-1").trim()
    }
}

/** Local media: HTTPS downloads, sample-copy muxing and the verification both paths share. */
object MediaTransfer {
    const val MAX_SAMPLE_BYTES = 16 * 1024 * 1024
    private const val MIME_VIDEO = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val MIME_AUDIO = MediaFormat.MIMETYPE_AUDIO_AAC

    /**
     * True when [file] really holds playable tracks: a video track, the audio track when the
     * descriptor promised one, a sane duration and at least one decodable sample per track.
     */
    fun verify(file: File, requireAudio: Boolean): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var video = false
            var audio = false
            var durationUs = 0L
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime == MIME_VIDEO) video = true
                if (mime == MIME_AUDIO) audio = true
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    durationUs = maxOf(durationUs, format.getLong(MediaFormat.KEY_DURATION))
                }
            }
            if (!video || (requireAudio && !audio) || durationUs <= 0L) return false
            return hasSample(file, MIME_VIDEO) && (!requireAudio || hasSample(file, MIME_AUDIO))
        } catch (error: Exception) {
            Log.w(TAG, "verify failed for ${file.name}", error)
            return false
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun hasSample(file: File, mime: String): Boolean {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val track = trackOf(extractor, mime) ?: return false
            extractor.selectTrack(track)
            val size = extractor.sampleSize
            if (size <= 0L || size > MAX_SAMPLE_BYTES) return false
            val buffer = ByteBuffer.allocateDirect(size.toInt())
            return extractor.readSampleData(buffer, 0) > 0
        } catch (error: Exception) {
            Log.w(TAG, "sample check failed for ${file.name}", error)
            return false
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * Copies the encoded samples of [video] and [audio] into one mp4, interleaving the two tracks by
     * sample time and leaving each track's own order untouched.
     */
    fun mux(video: File, audio: File, target: File, isCanceled: () -> Boolean) {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        var ok = false
        try {
            videoExtractor.setDataSource(video.absolutePath)
            audioExtractor.setDataSource(audio.absolutePath)
            val videoTrack = trackOf(videoExtractor, MIME_VIDEO) ?: throw BridgeException("unsupportedMedia", NO_VIDEO)
            val audioTrack = trackOf(audioExtractor, MIME_AUDIO) ?: throw BridgeException("unsupportedMedia", MERGE_FAILED)
            videoExtractor.selectTrack(videoTrack)
            audioExtractor.selectTrack(audioTrack)
            val videoFormat = videoExtractor.getTrackFormat(videoTrack)
            val audioFormat = audioExtractor.getTrackFormat(audioTrack)
            // One sample per track is staged before the muxer exists: the muxer only ever
            // starts with both tracks proven non-empty, so stop()/release() can never meet
            // the empty-track state that stagefright answers with a native abort.
            if (isCanceled()) throw BridgeException("downloadCanceled", CANCELED)
            val firstVideo = readSample(videoExtractor) ?: throw BridgeException("unsupportedMedia", MERGE_FAILED)
            if (isCanceled()) throw BridgeException("downloadCanceled", CANCELED)
            val firstAudio = readSample(audioExtractor) ?: throw BridgeException("unsupportedMedia", MERGE_FAILED)
            val output = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = output
            if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                output.setOrientationHint(videoFormat.getInteger(MediaFormat.KEY_ROTATION))
            }
            val videoOut = output.addTrack(videoFormat)
            val audioOut = output.addTrack(audioFormat)
            output.start()
            started = true
            val buffer = SampleBuffer()
            val info = MediaCodec.BufferInfo()
            // Times stay raw: AAC edit lists start before zero, and only a post-advance -1
            // means exhausted. Written PTS values are never altered.
            if (firstVideo.timeUs <= firstAudio.timeUs) {
                writeSample(output, videoOut, firstVideo, info)
                writeSample(output, audioOut, firstAudio, info)
            } else {
                writeSample(output, audioOut, firstAudio, info)
                writeSample(output, videoOut, firstVideo, info)
            }
            var videoSamples = 1
            var audioSamples = 1
            var videoTime = if (videoExtractor.advance()) videoExtractor.sampleTime else -1L
            var audioTime = if (audioExtractor.advance()) audioExtractor.sampleTime else -1L
            var videoDone = videoTime < 0L
            var audioDone = audioTime < 0L
            while (!videoDone || !audioDone) {
                if (isCanceled()) throw BridgeException("downloadCanceled", CANCELED)
                val fromVideo = audioDone || (!videoDone && videoTime <= audioTime)
                if (fromVideo) {
                    copySample(videoExtractor, videoOut, output, buffer, info)
                    videoSamples++
                    if (videoExtractor.advance()) {
                        videoTime = videoExtractor.sampleTime
                    } else {
                        videoDone = true
                    }
                } else {
                    copySample(audioExtractor, audioOut, output, buffer, info)
                    audioSamples++
                    if (audioExtractor.advance()) {
                        audioTime = audioExtractor.sampleTime
                    } else {
                        audioDone = true
                    }
                }
            }
            // A stop() that throws must reach the caller: only then is the output known bad.
            output.stop()
            started = false
            ok = true
        } catch (error: BridgeException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "mux failed for ${target.name}", error)
            throw BridgeException("unsupportedMedia", MERGE_FAILED)
        } finally {
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
            // A canceled or failed mux never leaves a file behind to be mistaken for output.
            if (!ok) runCatching { target.delete() }
        }
    }

    private class HeldSample(val data: java.nio.ByteBuffer, val size: Int, val timeUs: Long, val flags: Int)

    private fun readSample(extractor: MediaExtractor): HeldSample? {
        val size = extractor.sampleSize
        if (size <= 0L || size > MAX_SAMPLE_BYTES) return null
        val data = java.nio.ByteBuffer.allocateDirect(size.toInt())
        val read = extractor.readSampleData(data, 0)
        if (read <= 0) return null
        return HeldSample(data, read, extractor.sampleTime, extractor.sampleFlags)
    }

    private fun writeSample(muxer: MediaMuxer, track: Int, sample: HeldSample, info: MediaCodec.BufferInfo) {
        info.offset = 0
        info.size = sample.size
        info.presentationTimeUs = sample.timeUs
        info.flags = sample.flags
        muxer.writeSampleData(track, sample.data, info)
    }

    private fun copySample(
        extractor: MediaExtractor,
        track: Int,
        muxer: MediaMuxer,
        buffer: SampleBuffer,
        info: MediaCodec.BufferInfo,
    ) {
        val size = extractor.sampleSize
        if (size <= 0L) throw BridgeException("unsupportedMedia", MERGE_FAILED)
        if (size > MAX_SAMPLE_BYTES) throw BridgeException("unsupportedMedia", TOO_LARGE)
        val destination = buffer.ensure(size.toInt())
        val read = extractor.readSampleData(destination, 0)
        if (read <= 0) throw BridgeException("unsupportedMedia", MERGE_FAILED)
        info.offset = 0
        info.size = read
        info.presentationTimeUs = extractor.sampleTime
        info.flags = extractor.sampleFlags
        muxer.writeSampleData(track, destination, info)
    }

    private fun trackOf(extractor: MediaExtractor, mime: String): Int? {
        for (index in 0 until extractor.trackCount) {
            if (extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) == mime) return index
        }
        return null
    }
}

/** One direct buffer reused for every sample, grown only when a sample needs it. */
private class SampleBuffer {
    private var buffer: ByteBuffer = ByteBuffer.allocateDirect(512 * 1024)

    fun ensure(size: Int): ByteBuffer {
        if (buffer.capacity() < size) buffer = ByteBuffer.allocateDirect(size)
        return buffer
    }
}
