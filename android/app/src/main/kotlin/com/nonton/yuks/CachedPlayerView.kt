package com.nonton.yuks

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory
import java.util.Collections
import java.util.IdentityHashMap

@UnstableApi
class CachedPlayerViewFactory(
    private val activity: Activity,
    private val messenger: BinaryMessenger,
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
    override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
        val id = ((args as? Map<*, *>)?.get("id") as? String).orEmpty()
        return CachedPlayerView(activity, messenger, viewId, id)
    }
}

@UnstableApi
class CachedPlayerView(
    private val activity: Activity,
    messenger: BinaryMessenger,
    viewId: Int,
    private val id: String,
) : PlatformView, Player.Listener {
    private val root = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
    private val video = PlayerView(activity).apply {
        useController = true
        controllerAutoShow = true
        controllerHideOnTouch = true
        controllerShowTimeoutMs = 3_000
        setShowPreviousButton(false)
        setShowNextButton(false)
        setShowShuffleButton(false)
        setShowSubtitleButton(false)
        setShowVrButton(false)
        setShowRewindButton(true)
        setShowFastForwardButton(true)
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
    }
    private val errorText = label().apply { gravity = Gravity.CENTER }
    private val errorPanel = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(16), dp(24), dp(16))
        setBackgroundColor(Color.BLACK)
        visibility = View.GONE
        addView(errorText)
        addView(Button(activity, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Coba putar lagi"
            isAllCaps = false
            setTextColor(Color.WHITE)
            minHeight = dp(48)
            minimumHeight = dp(48)
            setOnClickListener { prepare() }
        }, LinearLayout.LayoutParams(-2, -2))
    }
    private val channel = MethodChannel(messenger, "com.nonton.yuks/player/$viewId")
    private var player: ExoPlayer? = null
    private var disposed = false
    private val speed = video.findViewById<ImageView>(androidx.media3.ui.R.id.exo_settings)
    private var speedMenu: PopupMenu? = null
    private var aspectRatio: Double? = null

    init {
        root.addView(video, FrameLayout.LayoutParams(-1, -1))
        root.addView(errorPanel, FrameLayout.LayoutParams(-1, -1))
        video.setFullscreenButtonClickListener { enabled ->
            if (!disposed) channel.invokeMethod("fullscreen", mapOf("enabled" to enabled))
        }
        video.setAspectRatioListener { targetAspectRatio, _, _ ->
            if (!disposed && targetAspectRatio.isFinite() && targetAspectRatio > 0f) {
                aspectRatio = targetAspectRatio.toDouble()
                sendAspectRatio()
            }
        }
        speed.setImageResource(androidx.media3.ui.R.drawable.exo_styled_controls_speed)
        updateSpeedDescription()
        speed.setOnClickListener { showSpeedMenu() }
        channel.setMethodCallHandler { call, result ->
            when (call.method) {
                "pause" -> { pause(); result.success(null) }
                "dispose" -> { dispose(); result.success(null) }
                "setFullscreen" -> {
                    val enabled = (call.arguments as? Map<*, *>)?.get("enabled") == true
                    video.setFullscreenButtonState(enabled)
                    result.success(null)
                }
                "ready" -> { sendAspectRatio(); result.success(null) }
                else -> result.notImplemented()
            }
        }
        instances.add(this)
        prepare()
    }

    private fun prepare() {
        if (disposed) return
        errorPanel.visibility = View.GONE
        try {
            // The manager authorizes task ownership and chooses legacy file or shared cache.
            val source = DownloadManager.playbackSource(activity.applicationContext, id)
            val active = player ?: ExoPlayer.Builder(activity)
                .setSeekBackIncrementMs(10_000)
                .setSeekForwardIncrementMs(10_000)
                .build().also {
                    player = it
                    it.setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                    it.setHandleAudioBecomingNoisy(true)
                    it.addListener(this)
                    video.player = it
                }
            val position = active.currentPosition
            active.setMediaSource(source, position)
            active.prepare()
            active.play()
        } catch (_: Exception) {
            showError("Video belum dapat dibuka. Kembali ke koleksi dan lanjutkan unduhan jika belum selesai, lalu coba putar lagi.")
        }
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (disposed) return
        root.keepScreenOn = player.isPlaying
        if (events.contains(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED)) updateSpeedDescription()
    }

    override fun onPlayerError(error: PlaybackException) {
        if (disposed) return
        showError(when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                "Video belum dapat dilanjutkan. Periksa koneksi, kembali ke koleksi untuk melanjutkan unduhan, lalu coba putar lagi."
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
                "Perangkat ini tidak dapat memutar format video ini. Kembali ke koleksi dan pilih resolusi lain saat mengunduh."
            else -> "Bagian video ini belum dapat diputar. Jika unduhan dijeda atau terputus, kembali ke koleksi dan lanjutkan unduhan. Data yang sudah tersimpan tidak dihapus."
        })
    }

    private fun showError(message: String) {
        pause()
        errorText.text = message
        errorPanel.visibility = View.VISIBLE
    }

    private fun sendAspectRatio() {
        if (disposed) return
        aspectRatio?.let { channel.invokeMethod("videoAspectRatio", mapOf("aspectRatio" to it)) }
    }

    private fun updateSpeedDescription() {
        val value = player?.playbackParameters?.speed ?: 1f
        speed.contentDescription = "Kecepatan pemutaran, ${speedLabel(value)}×"
    }

    private fun showSpeedMenu() {
        if (disposed || speedMenu != null) return
        val active = player ?: return
        val values = SPEEDS
        val popup = PopupMenu(activity, speed)
        values.forEachIndexed { index, value ->
            popup.menu.add(0, index, index, "${speedLabel(value)}×")
                .setCheckable(true).isChecked = active.playbackParameters.speed == value
        }
        popup.menu.setGroupCheckable(0, true, true)
        popup.setOnMenuItemClickListener { item ->
            if (!disposed) {
                active.setPlaybackSpeed(values[item.itemId])
                updateSpeedDescription()
            }
            true
        }
        popup.setOnDismissListener {
            speedMenu = null
            if (!disposed) {
                video.controllerShowTimeoutMs = 3_000
                video.controllerHideOnTouch = true
                video.showController()
            }
        }
        speedMenu = popup
        video.controllerShowTimeoutMs = 0
        video.controllerHideOnTouch = false
        video.showController()
        popup.show()
    }

    private fun pause() {
        player?.pause()
        root.keepScreenOn = false
    }

    override fun getView(): View = root

    override fun dispose() {
        if (disposed) return
        disposed = true
        speedMenu?.dismiss()
        speedMenu = null
        video.setAspectRatioListener(null)
        video.setFullscreenButtonClickListener(null)
        channel.setMethodCallHandler(null)
        root.keepScreenOn = false
        video.player = null
        player?.removeListener(this)
        player?.release()
        player = null
        instances.remove(this)
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private fun label() = TextView(activity).apply {
        setTextColor(Color.WHITE)
        textSize = 14f
    }

    companion object {
        private val SPEEDS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)
        private val instances = Collections.newSetFromMap(IdentityHashMap<CachedPlayerView, Boolean>())

        @JvmStatic
        fun pauseAll() { instances.toList().forEach { it.pause() } }

        private fun speedLabel(speed: Float): String = speed.toString().removeSuffix(".0").replace('.', ',')
    }
}
