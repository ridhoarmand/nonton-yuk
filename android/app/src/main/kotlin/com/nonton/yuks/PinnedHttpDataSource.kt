package com.nonton.yuks

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Locale
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.math.min

internal const val CACHE_PAUSED = "Bagian video ini belum diunduh. Lanjutkan unduhan untuk menonton bagian ini."
internal const val CACHE_CHANGED = "Berkas di server berubah atau tidak mendukung lanjut unduh. Byte lama tetap tersimpan; batalkan hanya jika ingin mengunduh ulang."
private const val NETWORK_ERROR = "Koneksi media terputus. Byte yang sudah diunduh tetap tersimpan; lanjutkan setelah jaringan pulih."
private const val BAD_RESPONSE = "Balasan server media tidak valid. Byte yang sudah diunduh tetap tersimpan."
private const val HEADER_LIMIT = 16 * 1024
private val SINGLE_RESPONSE_HEADERS = setOf("content-length", "transfer-encoding", "content-range",
    "location", "etag", "last-modified", "content-encoding", "content-type", "date")

internal interface CancelableUpstream {
    fun cancel()
}

internal data class CacheResponse(val length: Long, val etag: String?, val lastModified: String?,
    val rangeSupported: Boolean? = null)

internal object PinnedHttpPolicy {
    private val allowedHeaders = setOf("user-agent", "referer", "origin", "accept", "accept-language")
    private val contentRange = Regex("""bytes (\d+)-(\d+)/(\d+|\*)""")

    fun uri(raw: String, platform: String): Uri {
        if (raw.isEmpty() || raw.length > 16 * 1024 || raw.any { it.code !in 0x21..0x7e }) reject()
        val uri = Uri.parse(raw)
        if (uri.scheme != "https" || uri.userInfo != null || uri.fragment != null ||
            (uri.port != -1 && uri.port != 443) || !Platforms.media(uri.host.orEmpty(), platform)
        ) reject()
        return uri
    }

    fun headers(headers: Map<String, String>) {
        var size = 0
        headers.forEach { (name, value) ->
            if (name.lowercase(Locale.US) !in allowedHeaders || value.isEmpty() ||
                value.any { it.code !in 0x20..0x7e }
            ) reject()
            size += name.length + value.length
        }
        if (size > 8 * 1024) reject()
    }

    fun publicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return false
        val bytes = address.address.map { it.toInt() and 255 }
        if (bytes.size == 4) {
            val a = bytes[0]
            val b = bytes[1]
            val c = bytes[2]
            return !(a == 0 || a == 10 || a == 127 || a >= 224 ||
                a == 100 && b in 64..127 || a == 169 && b == 254 ||
                a == 172 && b in 16..31 || a == 192 && (b == 168 || b == 0 || b == 2) ||
                a == 198 && (b == 18 || b == 19 || b == 51 && c == 100) ||
                a == 203 && b == 0 && c == 113)
        }
        // Only globally routable unicast IPv6; also excludes ULA, mapped IPv4 and NAT64.
        return bytes.size == 16 && bytes[0] in 0x20..0x3f &&
            !(bytes[0] == 0x20 && bytes[1] == 0x01 &&
                (bytes[2] < 2 || bytes[2] == 0x0d && bytes[3] == 0xb8)) &&
            !(bytes[0] == 0x20 && bytes[1] == 0x02)
    }

    fun range(value: String?): Triple<Long, Long, Long> {
        val match = value?.let { contentRange.matchEntire(it) } ?: reject()
        val start = match.groupValues[1].toLongOrNull() ?: reject()
        val end = match.groupValues[2].toLongOrNull() ?: reject()
        val total = if (match.groupValues[3] == "*") C.LENGTH_UNSET.toLong()
            else match.groupValues[3].toLongOrNull() ?: reject()
        if (end < start || end == Long.MAX_VALUE || total != C.LENGTH_UNSET.toLong() && total <= end) reject()
        return Triple(start, end, total)
    }

    fun strongEtag(value: String?): String? = value?.takeIf {
        it.length in 2..1024 && it.startsWith('"') && it.endsWith('"') &&
            it.substring(1, it.length - 1).all { char -> char.code in 0x21..0x7e && char != '"' }
    }

    fun lastModified(value: String?, date: String?): String? {
        if (value == null || date == null || value.length !in 1..128 || date.length !in 1..128) return null
        return try {
            val modified = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            val served = ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            value.takeIf { served.epochSecond - modified.epochSecond >= 60L }
        } catch (_: Exception) { null }
    }

    fun reject(): Nothing = throw IOException(BAD_RESPONSE)
}

/** Connects to a checked address, keeping the original TLS hostname and rechecking every redirect. */
internal class PinnedHttpDataSource(
    private val platform: String,
    private val headers: Map<String, String>,
    private val expected: CacheResponse,
    private val onResponse: (CacheResponse) -> Unit,
    private val keepSequentialBody: Boolean = false,
) : BaseDataSource(true), CancelableUpstream {
    @Volatile private var canceled = false
    @Volatile private var plain: Socket? = null
    @Volatile private var secure: SSLSocket? = null
    @Volatile private var lookup: FutureTask<Array<InetAddress>>? = null
    private var opened = false
    private var currentUri: Uri? = null
    private var body: HttpBody? = null
    private var remaining = C.LENGTH_UNSET.toLong()
    private var position = 0L
    private var received = 0L
    private var responseLength = C.LENGTH_UNSET.toLong()
    private var responseMetadata = expected

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        try {
            checkCanceled()
            PinnedHttpPolicy.headers(headers)
            var uri = PinnedHttpPolicy.uri(dataSpec.uri.toString(), platform)
            currentUri = uri
            position = dataSpec.position
            var redirects = 0
            while (true) {
                checkCanceled()
                val response = connect(uri, dataSpec)
                val status = response.first
                val head = response.second
                if (status in setOf(301, 302, 303, 307, 308)) {
                    if (++redirects > 5) PinnedHttpPolicy.reject()
                    val location = head["location"] ?: PinnedHttpPolicy.reject()
                    uri = PinnedHttpPolicy.uri(URL(URL(uri.toString()), location).toString(), platform)
                    closeSockets()
                    continue
                }
                val encoding = head["content-encoding"]
                if (encoding != null && !encoding.equals("identity", true)) PinnedHttpPolicy.reject()
                val contentLength = head["content-length"]?.let { raw ->
                    raw.toLongOrNull()?.takeIf { it >= 0 } ?: PinnedHttpPolicy.reject()
                }
                val type = head["content-type"]?.substringBefore(';')?.trim()?.lowercase(Locale.US)
                if (status in 200..206 && type != null && (type.startsWith("text/") || listOf("json", "html", "xml", "javascript").any { it in type })) {
                    throw IOException("Server mengirim halaman, bukan berkas media.")
                }
                var bodyLength = contentLength ?: C.LENGTH_UNSET.toLong()
                responseLength = when (status) {
                    206 -> {
                        val (start, end, total) = PinnedHttpPolicy.range(head["content-range"])
                        val wantedEnd = if (dataSpec.length == C.LENGTH_UNSET.toLong()) Long.MAX_VALUE
                            else Math.addExact(position, dataSpec.length - 1)
                        if (start != position || end > wantedEnd || contentLength != null && contentLength != end - start + 1) {
                            PinnedHttpPolicy.reject()
                        }
                        bodyLength = end - start + 1
                        if (end < wantedEnd && (total < 0 || end + 1 != total)) PinnedHttpPolicy.reject()
                        total
                    }
                    200 -> {
                        contentLength ?: C.LENGTH_UNSET.toLong()
                    }
                    416 -> {
                        val total = head["content-range"]?.removePrefix("bytes */")?.toLongOrNull()
                        if (total == null || total != position || total <= 0L) throw IOException(CACHE_CHANGED)
                        validateMetadata(CacheResponse(total, PinnedHttpPolicy.strongEtag(head["etag"]),
                            PinnedHttpPolicy.lastModified(head["last-modified"], head["date"])))
                        remaining = 0L
                        opened = true
                        transferStarted(dataSpec)
                        return 0L
                    }
                    401, 403, 410 -> throw IOException("Tautan unduhan kedaluwarsa atau ditolak. Byte lama tetap tersimpan; coba lanjutkan nanti.")
                    412 -> throw IOException(CACHE_CHANGED)
                    else -> PinnedHttpPolicy.reject()
                }
                responseMetadata = CacheResponse(responseLength, PinnedHttpPolicy.strongEtag(head["etag"]),
                    PinnedHttpPolicy.lastModified(head["last-modified"], head["date"]), status == 206)
                validateMetadata(responseMetadata)
                val stream = BufferedInputStream(checkNotNull(secure).inputStream, 64 * 1024)
                val transferEncoding = head["transfer-encoding"]
                if (transferEncoding != null && (!transferEncoding.equals("chunked", true) || contentLength != null)) PinnedHttpPolicy.reject()
                body = HttpBody(stream, bodyLength, transferEncoding != null)
                val available = if (bodyLength < 0L || status != 200) bodyLength else bodyLength - position
                if (available < 0L && bodyLength >= 0L) throw IOException(CACHE_CHANGED)
                val requested = if (status == 200 && keepSequentialBody) C.LENGTH_UNSET.toLong() else dataSpec.length
                remaining = when {
                    requested == C.LENGTH_UNSET.toLong() -> available
                    available == C.LENGTH_UNSET.toLong() -> requested
                    else -> min(available, requested)
                }
                opened = true
                transferStarted(dataSpec)
                if (status == 200 && position > 0L) {
                    val skip = ByteArray(64 * 1024)
                    var left = position
                    while (left > 0L) {
                        checkCanceled()
                        val count = checkNotNull(body).read(skip, 0, min(left, skip.size.toLong()).toInt())
                        if (count <= 0) throw IOException(CACHE_CHANGED)
                        left -= count
                        bytesTransferred(count)
                    }
                }
                return remaining
            }
        } catch (error: Exception) {
            closeSockets()
            if (error is IOException && error.message in setOf(BAD_RESPONSE, CACHE_CHANGED, CACHE_PAUSED,
                    "Server mengirim halaman, bukan berkas media.",
                    "Tautan unduhan kedaluwarsa atau ditolak. Byte lama tetap tersimpan; coba lanjutkan nanti.")) throw error
            throw IOException(if (canceled) CACHE_PAUSED else NETWORK_ERROR)
        }
    }

    private fun validateMetadata(actual: CacheResponse) {
        if (expected.length >= 0 && expected.length != actual.length ||
            expected.etag != null && expected.etag != actual.etag ||
            expected.etag == null && expected.lastModified != null && expected.lastModified != actual.lastModified
        ) throw IOException(CACHE_CHANGED)
        onResponse(actual)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        checkCanceled()
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        try {
            val count = checkNotNull(body).read(buffer, offset,
                if (remaining < 0L) length else min(length.toLong(), remaining).toInt())
            if (count < 0) {
                if (responseLength < 0L) {
                    responseLength = position + received
                    onResponse(responseMetadata.copy(length = responseLength))
                }
                remaining = 0L
                return C.RESULT_END_OF_INPUT
            }
            received += count
            if (remaining >= 0L) remaining -= count
            bytesTransferred(count)
            return count
        } catch (error: IOException) {
            throw IOException(if (canceled) CACHE_PAUSED else NETWORK_ERROR)
        }
    }

    override fun getUri(): Uri? = currentUri

    override fun cancel() {
        canceled = true
        lookup?.cancel(true)
        closeSockets()
    }

    override fun close() {
        closeSockets()
        body = null
        currentUri = null
        received = 0L
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private fun checkCanceled() {
        if (canceled) throw IOException(CACHE_PAUSED)
    }

    private fun closeSockets() {
        runCatching { secure?.close() }
        runCatching { plain?.close() }
        secure = null
        plain = null
    }

    private fun connect(uri: Uri, spec: DataSpec): Pair<Int, Map<String, String>> {
        val host = checkNotNull(uri.host)
        val task = FutureTask { InetAddress.getAllByName(host) }
        lookup = task
        Thread(task, "media-dns").apply { isDaemon = true; start() }
        val addresses = try { task.get(5, TimeUnit.SECONDS) } finally { task.cancel(true); lookup = null }
        checkCanceled()
        if (addresses.isEmpty() || addresses.any { !PinnedHttpPolicy.publicAddress(it) }) PinnedHttpPolicy.reject()
        val socket = Socket()
        plain = socket
        checkCanceled()
        socket.soTimeout = 20_000
        socket.connect(InetSocketAddress(addresses.first(), 443), 10_000)
        checkCanceled()
        val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(socket, host, 443, true) as SSLSocket
        secure = tls
        checkCanceled()
        tls.soTimeout = 20_000
        tls.startHandshake()
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, tls.session)) PinnedHttpPolicy.reject()
        checkCanceled()
        val path = uri.encodedPath.orEmpty().ifEmpty { "/" } + uri.encodedQuery?.let { "?$it" }.orEmpty()
        val request = buildString {
            append("GET ").append(path).append(" HTTP/1.1\r\nHost: ").append(host)
            append("\r\nAccept-Encoding: identity\r\nConnection: close\r\n")
            headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            append("Range: bytes=").append(spec.position).append('-')
            if (spec.length >= 0L) append(Math.addExact(spec.position, spec.length - 1))
            append("\r\n")
            expected.etag?.let { append("If-Match: ").append(it).append("\r\nIf-Range: ").append(it).append("\r\n") }
                ?: expected.lastModified?.let { append("If-Unmodified-Since: ").append(it).append("\r\nIf-Range: ").append(it).append("\r\n") }
            append("\r\n")
        }
        tls.outputStream.write(request.toByteArray(Charsets.ISO_8859_1))
        tls.outputStream.flush()
        // Read the head without prefetching: the body buffer is created only after validation.
        val input = tls.inputStream
        val bytes = ByteArrayOutputStream()
        var tail = 0
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (tail != 0x0d0a0d0a) {
            checkCanceled()
            if (System.nanoTime() > deadline || bytes.size() >= HEADER_LIMIT) PinnedHttpPolicy.reject()
            val byte = input.read()
            if (byte < 0) PinnedHttpPolicy.reject()
            bytes.write(byte)
            tail = tail shl 8 or byte
        }
        val lines = bytes.toString("ISO-8859-1").split("\r\n")
        val statusLine = lines.first().split(' ')
        if (statusLine.size < 2 || statusLine[0] !in setOf("HTTP/1.0", "HTTP/1.1")) PinnedHttpPolicy.reject()
        val status = statusLine[1].toIntOrNull()?.takeIf { it in 100..599 } ?: PinnedHttpPolicy.reject()
        val result = LinkedHashMap<String, String>()
        lines.drop(1).filter { it.isNotEmpty() }.forEach { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) PinnedHttpPolicy.reject()
            val name = line.substring(0, colon).lowercase(Locale.US)
            if (name.any { !it.isLetterOrDigit() && it != '-' }) PinnedHttpPolicy.reject()
            val value = line.substring(colon + 1).trim()
            if (value.any { it.code !in 0x20..0x7e } || name in result && name in SINGLE_RESPONSE_HEADERS) PinnedHttpPolicy.reject()
            result.putIfAbsent(name, value)
        }
        return status to result
    }
}

internal class HttpBody(
    private val input: InputStream,
    private var remaining: Long,
    private val chunked: Boolean,
) {
    private var chunkRemaining = 0L
    private var chunkCrlf = false
    private var done = false

    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (done || !chunked && remaining == 0L) return -1
        if (chunked && chunkRemaining == 0L && !nextChunk()) return -1
        val limit = if (chunked) chunkRemaining else remaining
        val count = input.read(buffer, offset, if (limit < 0L) length else min(length.toLong(), limit).toInt())
        if (count < 0 && (chunked || remaining > 0)) throw EOFException(BAD_RESPONSE)
        if (count > 0) {
            if (remaining >= 0L) remaining -= count
            if (chunked) {
                chunkRemaining -= count
                chunkCrlf = true
                if (remaining == 0L && (chunkRemaining != 0L || nextChunk())) PinnedHttpPolicy.reject()
            }
        }
        return count
    }

    private fun nextChunk(): Boolean {
        if (chunkCrlf && line().isNotEmpty()) PinnedHttpPolicy.reject()
        val size = line().substringBefore(';')
        if (size.isEmpty() || size.any { it !in "0123456789abcdefABCDEF" }) PinnedHttpPolicy.reject()
        chunkRemaining = size.toLongOrNull(16) ?: PinnedHttpPolicy.reject()
        if (remaining >= 0L && chunkRemaining > remaining) PinnedHttpPolicy.reject()
        if (chunkRemaining > 0L) return true
        var trailerSize = 0
        while (true) {
            val trailer = line()
            trailerSize += trailer.length + 2
            if (trailerSize > HEADER_LIMIT) PinnedHttpPolicy.reject()
            if (trailer.isEmpty()) break
        }
        if (remaining > 0L) throw EOFException(BAD_RESPONSE)
        done = true
        return false
    }

    private fun line(): String {
        val out = ByteArrayOutputStream()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (true) {
            if (System.nanoTime() > deadline) throw IOException(NETWORK_ERROR)
            val byte = input.read()
            if (byte < 0) throw EOFException(BAD_RESPONSE)
            if (byte == '\r'.code) {
                if (input.read() != '\n'.code) PinnedHttpPolicy.reject()
                return out.toString("ISO-8859-1")
            }
            if (byte == '\n'.code || out.size() >= HEADER_LIMIT) PinnedHttpPolicy.reject()
            out.write(byte)
        }
    }
}
