package com.nonton.yuks

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Channel plumbing only: shares the fixed method table with Dart and forwards the download queue's
 * progress events. All download behaviour lives in [DownloadManager].
 */
class MainActivity : FlutterActivity() {
    private val pendingShares = ArrayDeque<String>()
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var platformChannel: MethodChannel? = null
    private var progressChannel: EventChannel? = null
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        if (savedInstanceState?.containsKey(PENDING_SHARES) == true) {
            savedInstanceState.getStringArrayList(PENDING_SHARES)?.forEach(::queueShare)
        } else {
            captureShare(intent)
        }
        super.onCreate(savedInstanceState)
        // A data-sync service is only visible through its notification; Android 13+ needs a grant.
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val messenger = flutterEngine.dartExecutor.binaryMessenger

        progressChannel = EventChannel(messenger, "com.nonton.yuks/progress").also { channel ->
            channel.setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    DownloadManager.attach(events)
                }

                override fun onCancel(arguments: Any?) {
                    DownloadManager.attach(null)
                }
            })
        }

        platformChannel = MethodChannel(messenger, "com.nonton.yuks/platform").also { channel ->
            channel.setMethodCallHandler { call, result ->
                val arguments = call.arguments as? Map<*, *>
                when (call.method) {
                    "getSettings" -> executeIo(
                        result, "settingsFailed", "Pengaturan tidak dapat dibaca.",
                    ) {
                        ApiClient.settings(applicationContext).toMap()
                    }
                    "setSettings" -> if (arguments == null) {
                        result.error("invalidArguments", "Pengaturan tidak valid.", null)
                    } else {
                        executeIo(
                            result, "settingsFailed", "Pengaturan tidak dapat disimpan.",
                        ) {
                            ApiClient.saveSettings(applicationContext, arguments)
                            null
                        }
                    }
                    "probe" -> {
                        val url = (arguments?.get("url") as? String)?.trim().orEmpty()
                        val platform = (arguments?.get("platform") as? String)?.trim().orEmpty()
                        when {
                            url.isEmpty() || !Platforms.known(platform) ->
                                result.error("invalidArguments", "Tautan tidak valid.", null)
                            else -> executeIo(
                                result,
                                "probeFailed",
                                "Video tidak dapat dibaca. Periksa tautan lalu coba lagi.",
                            ) {
                                DownloadManager.probe(url, platform)
                            }
                        }
                    }
                    "enqueue" -> if (arguments == null) {
                        result.error("invalidArguments", "Unduhan tidak valid.", null)
                    } else {
                        executeIo(result, "enqueueFailed", "Unduhan tidak dapat dimulai.") {
                            DownloadManager.enqueue(applicationContext, arguments)
                        }
                    }
                    "cancel" -> withId(result, arguments) { id ->
                        DownloadManager.cancel(applicationContext, id)
                        null
                    }
                    "pause" -> withId(result, arguments) { id ->
                        DownloadManager.pause(applicationContext, id)
                        null
                    }
                    "resume" -> withId(result, arguments) { id ->
                        DownloadManager.resume(applicationContext, id)
                        null
                    }
                    "networkType" -> {
                        result.success(networkType())
                        null
                    }
                    "delete" -> withId(result, arguments) { id ->
                        DownloadManager.delete(applicationContext, id)
                        null
                    }
                    "listDownloads" -> executeIo(
                        result, "listFailed", "Daftar unduhan tidak dapat dibaca.",
                    ) {
                        DownloadManager.list(applicationContext)
                    }
                    "beginPlayerSession" -> beginPlayerSession(result)
                    "setPlayerBrightness" -> {
                        val value = (arguments?.get("value") as? Number)?.toDouble()
                        setPlayerBrightness(result, value)
                    }
                    "endPlayerSession" -> {
                        endPlayerSession()
                        result.success(null)
                    }
                    "takePendingShares" -> {
                        val shares = pendingShares.toList()
                        pendingShares.clear()
                        result.success(shares)
                    }
                    else -> result.notImplemented()
                }
            }
            if (pendingShares.isNotEmpty()) channel.invokeMethod("shareAvailable", null)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (captureShare(intent)) platformChannel?.invokeMethod("shareAvailable", null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList(PENDING_SHARES, ArrayList(pendingShares))
        super.onSaveInstanceState(outState)
    }

    /** wifi, cellular, or unknown: the data-warning dialog needs no exact subtype. */
    private fun networkType(): String = try {
        val manager = getSystemService(CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            ?: return "unknown"
        val network = manager.activeNetwork ?: return "unknown"
        val caps = manager.getNetworkCapabilities(network) ?: return "unknown"
        when {
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "unknown"
        }
    } catch (_: Exception) {
        "unknown"
    }

    private fun captureShare(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return false
        val text = try {
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
        } catch (_: RuntimeException) {
            return false
        } ?: return false
        if (text.length !in 1..MAX_SHARE_CHARACTERS) return false
        return queueShare(text.toString())
    }

    private fun queueShare(text: String): Boolean {
        // Preserve queued shares; reject new overflow rather than evicting unread text.
        if (pendingShares.size >= MAX_PENDING_SHARES ||
            text.length !in 1..MAX_SHARE_CHARACTERS
        ) return false
        pendingShares.addLast(text)
        assert(pendingShares.size <= MAX_PENDING_SHARES)
        return true
    }

    private fun withId(
        result: MethodChannel.Result,
        arguments: Map<*, *>?,
        operation: (String) -> Any?,
    ) {
        val id = (arguments?.get("id") as? String)?.trim().orEmpty()
        if (id.isEmpty()) {
            result.error("invalidArguments", "Unduhan tidak valid.", null)
            return
        }
        executeIo(result, "downloadFailed", "Unduhan tidak dapat diubah.", { operation(id) })
    }

    private fun executeIo(
        result: MethodChannel.Result,
        errorCode: String,
        errorMessage: String,
        operation: () -> Any?,
    ) {
        try {
            ioExecutor.execute {
                try {
                    val value = operation()
                    runOnUiThread { if (!destroyed) result.success(value) }
                } catch (error: BridgeException) {
                    runOnUiThread {
                        if (!destroyed) result.error(error.code, error.message, null)
                    }
                } catch (_: Exception) {
                    runOnUiThread {
                        if (!destroyed) result.error(errorCode, errorMessage, null)
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            if (!destroyed) result.error(errorCode, errorMessage, null)
        }
    }

    override fun onPause() {
        super.onPause()
        // The window override belongs to the visible player only; never leak it to another app.
        endPlayerSession()
    }

    override fun onDestroy() {
        destroyed = true
        endPlayerSession()
        DownloadManager.attach(null)
        platformChannel?.setMethodCallHandler(null)
        platformChannel = null
        progressChannel?.setStreamHandler(null)
        progressChannel = null
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    // Player window session: app-window brightness only, never the system setting.
    private var sessionBrightness: Float? = null
    private var addedKeepScreenOn = false

    private fun beginPlayerSession(result: MethodChannel.Result) {
        if (sessionBrightness == null) {
            val attributes = window.attributes
            sessionBrightness = attributes.screenBrightness
            if (attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON == 0) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                addedKeepScreenOn = true
            }
        }
        result.success(mapOf("brightness" to effectiveBrightness()))
    }

    private fun setPlayerBrightness(result: MethodChannel.Result, value: Double?) {
        if (sessionBrightness == null) {
            result.error("noPlayerSession", "Sesi pemutar belum dimulai.", null)
            return
        }
        if (value == null || !value.isFinite() || value < 0.05 || value > 1.0) {
            result.error("invalidArguments", "Kecerahan harus 0,05 sampai 1.", null)
            return
        }
        val attributes = window.attributes
        attributes.screenBrightness = value.toFloat()
        window.attributes = attributes
        result.success(null)
    }

    private fun endPlayerSession() {
        val original = sessionBrightness ?: return
        sessionBrightness = null
        val attributes = window.attributes
        attributes.screenBrightness = original
        window.attributes = attributes
        if (addedKeepScreenOn) {
            addedKeepScreenOn = false
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** Current window override, falling back to a read-only peek at the system value. */
    private fun effectiveBrightness(): Float {
        val current = window.attributes.screenBrightness
        if (current >= 0f) return current.coerceIn(0.05f, 1f)
        val system = runCatching {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrNull()
        return if (system != null && system > 0) (system / 255f).coerceIn(0.05f, 1f) else 0.5f
    }

    companion object {
        private const val PENDING_SHARES = "nontonin.pendingShares"
        private const val MAX_PENDING_SHARES = 8
        private const val MAX_SHARE_CHARACTERS = 8_192
        private const val NOTIFICATION_REQUEST = 1
    }
}
