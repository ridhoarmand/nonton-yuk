package com.nonton.yuks

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Failure carrying the code and the Indonesian message the bridge reports to Dart. */
class BridgeException(val code: String, message: String) : Exception(message)

/** Hosts trusted per platform, mirrored exactly from lib/platforms.dart. */
object Platforms {
    // Source hosts are an exact set (never suffixes): evil subdomains fail here, not at the backend.
    private val sources = mapOf(
        "facebook" to setOf(
            "facebook.com", "www.facebook.com", "m.facebook.com", "web.facebook.com",
            "fb.watch", "www.fb.watch",
        ),
        "youtube" to setOf(
            "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com",
            "youtu.be", "www.youtu.be",
        ),
    )
    private val media = mapOf(
        "facebook" to listOf("facebook.com", "fbcdn.net"),
        "youtube" to listOf("googlevideo.com"),
    )
    private val thumbnails = mapOf(
        "facebook" to listOf("facebook.com", "fbcdn.net"),
        "youtube" to listOf("ytimg.com"),
    )

    fun known(platform: String): Boolean = sources.containsKey(platform)

    fun source(host: String, platform: String): Boolean = sources[platform]?.contains(host.lowercase()) == true

    fun media(host: String, platform: String): Boolean = matches(media[platform], host)

    fun thumbnail(host: String, platform: String): Boolean = matches(thumbnails[platform], host)

    /** Boundary suffix match: a suffix only counts as the whole host or after a dot. */
    private fun matches(suffixes: List<String>?, host: String): Boolean {
        val lowered = host.lowercase()
        return suffixes.orEmpty().any { lowered == it || lowered.endsWith(".$it") }
    }
}

/** One media stream as the backend described it: ordered parts, credentials-free headers. */
class Stream(
    val kind: String,
    val parts: List<String>,
    val headers: Map<String, String>,
    val approxBytes: Long,
)

/** Everything a download needs, already validated against the request that produced it. */
class Resolved(
    val platform: String,
    val videoId: String,
    val sourceUrl: String,
    val title: String,
    val uploader: String?,
    val durationMs: Long,
    val thumbnailUrl: String?,
    val height: Int,
    val heights: List<Int>,
    val approxBytes: Long,
    val streams: List<Stream>,
)

/**
 * The backend resolver and its settings. The token leaves this file only as an Authorization header
 * towards the configured backend origin; media hosts never see it.
 */
object ApiClient {
    private const val TAG = "NontoninApi"
    private const val PREFS = "settings"
    private const val KEY_THEME = "themeMode"
    // No secrets in source: injected at build time from NONTON_YUKS_API_ORIGIN/_TOKEN env
    // (or gradle.properties nontonYuksApiOrigin/nontonYuksApiToken); see README.
    private const val NOT_CONFIGURED = "Backend belum dikonfigurasi. Isi NONTON_YUKS_API_ORIGIN dan NONTON_YUKS_API_TOKEN lalu build ulang."

    private const val CONNECT_TIMEOUT = 10_000
    private const val READ_TIMEOUT = 50_000
    private const val RESPONSE_CAP = 256 * 1024
    private const val MAX_TITLE = 512
    private const val MAX_UPLOADER = 256
    private const val MAX_PARTS = 128
    private const val MAX_PART_URL = 16 * 1024
    private const val MAX_HEADER_BYTES = 8 * 1024
    private const val BAD = "Balasan backend tidak dikenali. Periksa backend lalu coba lagi."
    private const val UNAUTHORIZED = "Token layanan ditolak. Hubungi pengembang aplikasi."
    private const val NETWORK = "Backend tidak dapat dihubungi. Periksa jaringan lalu coba lagi."
    private val THEMES = setOf("system", "light", "dark")
    private val YOUTUBE_ID = Regex("[A-Za-z0-9_-]{11}")
    private val NUMERIC_ID = Regex("\\d{6,}")
    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{1,128}")
    private val ALLOWED_HEADERS = setOf("user-agent", "referer", "origin", "accept", "accept-language")

    class Settings(val themeMode: String) {
        fun toMap(): Map<String, String> = mapOf("themeMode" to themeMode)
    }

    fun settings(context: Context): Settings = prefs(context).let { prefs ->
        Settings(
            prefs.getString(KEY_THEME, "light").orEmpty().takeIf { it in THEMES } ?: "light",
        )
    }

    fun saveSettings(context: Context, arguments: Map<*, *>) {
        val theme = (arguments["themeMode"] as? String).orEmpty().trim().ifEmpty { "light" }
        if (theme !in THEMES) throw BridgeException("invalidSettings", "Tema tidak dikenal.")
        prefs(context).edit().putString(KEY_THEME, theme).apply()
    }

    /** Validates a user-supplied link natively as well; returns the parsed uri. */
    fun requireSourceUrl(platform: String, url: String): Uri {
        if (!Platforms.known(platform)) {
            throw BridgeException("unsupportedPlatform", "Platform ini belum didukung.")
        }
        return sourceUri(platform, url)
    }

    /** POSTs to the backend and returns the validated descriptor for [sourceUrl]. */
    fun resolve(sourceUrl: String, platform: String, height: Int = 0): Resolved {
        val requested = requireSourceUrl(platform, sourceUrl)
        val payload = JSONObject().put("text", sourceUrl)
        if (height > 0) payload.put("height", height)
        return parse(post(payload.toString()), platform, requested, height)
    }

    private fun post(body: String): String {
        val origin = BuildConfig.API_ORIGIN.trim()
        val token = BuildConfig.API_TOKEN
        if (origin.isEmpty() || token.isEmpty()) throw BridgeException("backendMissing", NOT_CONFIGURED)
        val connection = try {
            URL("${BuildConfig.API_ORIGIN.trimEnd('/')}/v1/extract").openConnection() as HttpURLConnection
        } catch (_: IOException) {
            throw BridgeException("backendNetwork", NETWORK)
        }
        connection.instanceFollowRedirects = false
        connection.connectTimeout = CONNECT_TIMEOUT
        connection.readTimeout = READ_TIMEOUT
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Authorization", "Bearer ${BuildConfig.API_TOKEN}")
        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val payload = stream?.let { read(it, RESPONSE_CAP) }.orEmpty()
            when {
                status == 401 -> throw BridgeException("backendUnauthorized", UNAUTHORIZED)
                status >= 400 -> throw backendFailure(status, payload)
                else -> payload
            }
        } catch (error: BridgeException) {
            throw error
        } catch (_: IOException) {
            throw BridgeException("backendNetwork", NETWORK)
        } finally {
            connection.disconnect()
        }
    }

    /** The backend envelope is forwarded verbatim; anything else is an unexpected shape. */
    private fun backendFailure(status: Int, payload: String): BridgeException {
        val error = try {
            JSONObject(payload).optJSONObject("error")
        } catch (_: JSONException) {
            null
        } ?: throw bad()
        val code = error.optString("code").trim()
        val message = error.optString("message").trim().take(300)
        if (code.isEmpty() || message.isEmpty()) throw bad()
        Log.w(TAG, "backend refused the request: $status $code")
        return BridgeException(code, message)
    }

    private fun read(stream: InputStream, cap: Int): String {
        val buffer = ByteArray(8 * 1024)
        val out = java.io.ByteArrayOutputStream()
        stream.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                if (out.size() > cap) throw bad()
            }
        }
        return out.toString("UTF-8")
    }

    private fun parse(payload: String, platform: String, requested: Uri, requestedHeight: Int = 0): Resolved {
        val root = try {
            JSONObject(payload)
        } catch (_: JSONException) {
            throw bad()
        }
        if (root.optString("platform").trim() != platform) throw bad()
        val videoId = root.optString("videoId").trim()
        if (!VIDEO_ID.matches(videoId)) throw bad()
        val source = root.optString("sourceUrl").trim()
        val canonical = sourceUri(platform, source)
        expectedVideoId(platform, requested)?.let { if (it != videoId) throw bad() }
        val title = root.optString("title").trim().take(MAX_TITLE)
        if (title.isEmpty()) throw bad()
        val uploader = string(root.opt("uploader"))?.trim()?.take(MAX_UPLOADER)?.ifEmpty { null }
        val durationMs = number(root, "durationMs")?.takeIf { it >= 0L } ?: throw bad()
        val heightValue = root.opt("height")
        val height = when {
            heightValue == null || heightValue == JSONObject.NULL -> 0
            heightValue is Number -> heightValue.toInt()
            else -> throw bad()
        }
        if (height < 0) throw bad()
        if (requestedHeight > 0 && height != requestedHeight) throw BridgeException(
            "qualityUnavailable", "Resolusi yang dipilih tidak tersedia lagi. Pilih resolusi lain.")
        val heights = (root.opt("heights") as? JSONArray)?.let { array ->
            (0 until array.length()).map { index ->
                (array.opt(index) as? Number)?.toInt()?.takeIf { it > 0 } ?: throw bad()
            }
        } ?: emptyList()
        if (heights.any { it <= 0 }) throw bad()
        val approxBytes = positive(root.opt("approxBytes"))
        val thumbnail = string(root.opt("thumbnailUrl"))
            ?.trim()
            ?.ifEmpty { null }
            ?.let { mediaUrl(platform, it, thumb = true) }
        val streams = (root.opt("streams") as? JSONArray)?.let { array ->
            (0 until array.length()).map { index ->
                array.opt(index) as? JSONObject ?: throw bad()
            }.map { stream(it, platform) }
        } ?: throw bad()
        val kinds = streams.map { it.kind }.toSet()
        val usable = streams.size == 1 && kinds == setOf("muxed") ||
            streams.size == 2 && kinds == setOf("video", "audio")
        if (!usable) throw bad()
        return Resolved(
            platform = platform,
            videoId = videoId,
            sourceUrl = canonical.toString(),
            title = title,
            uploader = uploader,
            durationMs = durationMs,
            thumbnailUrl = thumbnail,
            height = height,
            heights = heights,
            approxBytes = approxBytes ?: 0L,
            streams = streams,
        )
    }

    private fun stream(json: JSONObject, platform: String): Stream {
        val kind = json.optString("kind").trim()
        if (kind != "muxed" && kind != "video" && kind != "audio") throw bad()
        val array = json.opt("parts") as? JSONArray ?: throw bad()
        if (array.length() !in 1..MAX_PARTS) throw bad()
        val parts = (0 until array.length()).map { index ->
            val raw = array.opt(index) as? String ?: throw bad()
            mediaUrl(platform, raw.trim(), thumb = false)
        }
        return Stream(kind, parts, headers(json.opt("headers")), positive(json.opt("approxBytes")) ?: 0L)
    }

    private fun headers(value: Any?): Map<String, String> {
        if (value == null || value == JSONObject.NULL) return emptyMap()
        val json = value as? JSONObject ?: throw bad()
        var total = 0
        val headers = LinkedHashMap<String, String>()
        json.keys().forEach { name ->
            val header = json.opt(name) as? String ?: throw bad()
            if (name.lowercase() !in ALLOWED_HEADERS) throw bad()
            // Printable ASCII only: the values are written into the request line as-is.
            if (header.isEmpty() || header.any { it.code !in 0x20..0x7e }) throw bad()
            total += name.length + header.length
            headers[name] = header
        }
        if (total > MAX_HEADER_BYTES) throw bad()
        return headers
    }

    /** https on 443, no credentials, no control characters, host inside the platform's suffixes. */
    private fun mediaUrl(platform: String, raw: String, thumb: Boolean): String {
        if (raw.isEmpty() || raw.length > MAX_PART_URL) throw bad()
        if (raw.any { it.code !in 0x21..0x7e }) throw bad()
        val uri = Uri.parse(raw)
        if (uri.scheme != "https") throw bad()
        if (uri.userInfo != null) throw bad()
        if (uri.port != -1 && uri.port != 443) throw bad()
        val host = uri.host ?: throw bad()
        val allowed = if (thumb) Platforms.thumbnail(host, platform) else Platforms.media(host, platform)
        if (!allowed) throw bad()
        return raw
    }

    private fun sourceUri(platform: String, url: String): Uri {
        if (url.isEmpty() || url.length > 2048) throw invalidUrl()
        if (url.any { it.code !in 0x21..0x7e }) throw invalidUrl()
        val uri = Uri.parse(url)
        if (uri.scheme != "https") throw invalidUrl()
        if (uri.userInfo != null) throw invalidUrl()
        if (uri.port != -1 && uri.port != 443) throw invalidUrl()
        val host = uri.host ?: throw invalidUrl()
        if (!Platforms.source(host, platform)) throw invalidUrl()
        return uri
    }

    private fun invalidUrl() =
        BridgeException("invalidUrl", "Tautan video tidak valid atau belum didukung.")

    /** Canonical form for duplicate comparison: YouTube aliases collapse to one watch URL. */
    fun canonicalSource(platform: String, url: String): String {
        if (platform != "youtube") return url
        return try {
            val uri = Uri.parse(url)
            val host = uri.host.orEmpty().lowercase()
            val path = uri.path.orEmpty().trimEnd('/')
            val id = when {
                host == "youtu.be" || host == "www.youtu.be" ->
                    path.trimStart('/').takeIf { YOUTUBE_ID.matches(it) }
                path == "/watch" ->
                    uri.getQueryParameters("v").singleOrNull()?.takeIf { YOUTUBE_ID.matches(it) }
                else -> Regex("^/(shorts|live|embed)/([A-Za-z0-9_-]{11})$")
                    .matchEntire(path)?.groupValues?.get(1)
            }
            if (id != null) "https://www.youtube.com/watch?v=$id" else url
        } catch (_: Exception) {
            url
        }
    }

    /** The video id the request already pins down, or null when ambiguous (backend decides). */
    private fun expectedVideoId(platform: String, uri: Uri): String? {
        val host = uri.host.orEmpty().lowercase()
        val path = uri.path.orEmpty().trimEnd('/')
        return when (platform) {
            "youtube" -> when {
                host == "youtu.be" || host == "www.youtu.be" ->
                    path.trimStart('/').takeIf { YOUTUBE_ID.matches(it) }
                path == "/watch" ->
                    uri.getQueryParameters("v").singleOrNull()?.takeIf { YOUTUBE_ID.matches(it) }
                else -> Regex("^/(shorts|live|embed)/([A-Za-z0-9_-]{11})$")
                    .matchEntire(path)?.groupValues?.get(1)
            }
            "facebook" -> when {
                host == "fb.watch" || host == "www.fb.watch" -> null
                path in setOf("/watch", "/video.php", "/video/video.php") ->
                    uri.getQueryParameters("v").singleOrNull()?.takeIf { NUMERIC_ID.matches(it) }
                else -> (Regex("^/reel/([0-9]+)$").matchEntire(path)
                    ?: Regex("^/[^/]+/videos/(?:[^/]+/)?([0-9]+)$").matchEntire(path))
                    ?.groupValues?.get(1)
            }
            else -> null
        }
    }

    private fun number(json: JSONObject, key: String): Long? = (json.opt(key) as? Number)?.toLong()

    /** Null for a JSON null or absent value; any other non-string is a bad shape. */
    private fun string(value: Any?): String? = when {
        value == null || value == JSONObject.NULL -> null
        value is String -> value
        else -> throw bad()
    }

    private fun positive(value: Any?): Long? = when (value) {
        null, JSONObject.NULL -> null
        is Number -> value.toLong().takeIf { it > 0L }
        else -> throw bad()
    }

    private fun bad() = BridgeException("badResponse", BAD)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
