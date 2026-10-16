package com.nonton.yuks

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
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
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TimeBar
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale

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
        useController = false
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
    }
    private val controls = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(0xDD000000.toInt())
        setPadding(dp(8), 0, dp(8), dp(4))
    }
    private val time = label().apply { setPadding(dp(8), 0, dp(8), 0) }
    private val timeline = DefaultTimeBar(activity).apply {
        contentDescription = "Posisi video"
        setPlayedColor(Color.WHITE)
        setScrubberColor(Color.WHITE)
        setBufferedColor(0x88FFFFFF.toInt())
        setUnplayedColor(0x44FFFFFF)
    }
    private val play = imageButton(android.R.drawable.ic_media_play, "Putar video")
    private val speed = textButton("1×", "Kecepatan pemutaran")
    private val fullscreen = imageButton(android.R.drawable.ic_menu_crop, "Layar penuh")
    private val loading = label().apply {
        text = "Menyiapkan video dari unduhan…"
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(16), dp(16), dp(16))
    }
    private val errorText = label().apply { gravity = Gravity.CENTER }
    private val errorPanel = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(16), dp(24), dp(16))
        setBackgroundColor(Color.BLACK)
        visibility = View.GONE
        addView(errorText)
        addView(textButton("Coba putar lagi", "Coba putar lagi").apply {
            setOnClickListener { prepare() }
        }, LinearLayout.LayoutParams(-2, dp(56)))
    }
    private val channel = MethodChannel(messenger, "com.nonton.yuks/player/$viewId")
    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var disposed = false
    private var scrubbing = false
    private var isFullscreen = false
    private val tick = object : Runnable {
        override fun run() {
            if (disposed) return
            updateControls()
            handler.postDelayed(this, 500)
        }
    }

    init {
        root.addView(video, FrameLayout.LayoutParams(-1, -1))
        root.addView(loading, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        root.addView(errorPanel, FrameLayout.LayoutParams(-1, -1))
        val status = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        status.addView(time, LinearLayout.LayoutParams(0, -2, 1f))
        status.addView(speed, LinearLayout.LayoutParams(-2, dp(48)))
        status.addView(fullscreen, LinearLayout.LayoutParams(dp(48), dp(48)))
        controls.addView(status)
        controls.addView(timeline, LinearLayout.LayoutParams(-1, dp(48)))
        val buttons = LinearLayout(activity).apply { gravity = Gravity.CENTER }
        val back = textButton("−10", "Mundur 10 detik").apply {
            setOnClickListener { player?.seekBack() }
        }
        val forward = textButton("+10", "Maju 10 detik").apply {
            setOnClickListener { player?.seekForward() }
        }
        for (button in listOf(back, play, forward)) {
            buttons.addView(button, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        controls.addView(buttons)
        root.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        play.setOnClickListener {
            val active = player ?: return@setOnClickListener
            if (active.playerError != null) {
                prepare()
            } else if (active.playbackState == Player.STATE_ENDED) {
                active.seekTo(0)
                active.play()
            } else if (active.playWhenReady) {
                pause()
            } else {
                active.play()
            }
        }
        speed.setOnClickListener {
            val active = player ?: return@setOnClickListener
            PopupMenu(activity, speed).apply {
                val values = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
                values.forEachIndexed { index, value ->
                    menu.add(0, index, index, "${speedLabel(value)}×")
                        .setCheckable(true).isChecked = active.playbackParameters.speed == value
                }
                menu.setGroupCheckable(0, true, true)
                setOnMenuItemClickListener { item ->
                    active.setPlaybackSpeed(values[item.itemId])
                    updateControls()
                    true
                }
                show()
            }
        }
        fullscreen.setOnClickListener {
            channel.invokeMethod("fullscreen", mapOf("enabled" to !isFullscreen))
        }
        timeline.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) { scrubbing = true }
            override fun onScrubMove(timeBar: TimeBar, position: Long) {
                time.text = "${clock(position)} / ${clock(duration())}"
            }
            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                scrubbing = false
                if (!canceled) player?.seekTo(position.coerceIn(0, duration()))
                updateControls()
            }
        })
        channel.setMethodCallHandler { call, result ->
            when (call.method) {
                "pause" -> { pause(); result.success(null) }
                "dispose" -> { dispose(); result.success(null) }
                "setFullscreen" -> {
                    isFullscreen = (call.arguments as? Map<*, *>)?.get("enabled") == true
                    fullscreen.contentDescription = if (isFullscreen) "Keluar layar penuh" else "Layar penuh"
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
        instances.add(this)
        prepare()
        handler.post(tick)
    }

    private fun prepare() {
        if (disposed) return
        errorPanel.visibility = View.GONE
        loading.visibility = View.VISIBLE
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
        root.keepScreenOn = player.isPlaying
        loading.visibility = if (player.playerError == null &&
            (player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_IDLE)
        ) View.VISIBLE else View.GONE
        updateControls()
    }

    override fun onPlayerError(error: PlaybackException) {
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
        loading.visibility = View.GONE
        errorText.text = message
        errorPanel.visibility = View.VISIBLE
    }

    private fun updateControls() {
        val active = player ?: return
        val ended = active.playbackState == Player.STATE_ENDED
        play.setImageResource(when {
            ended -> android.R.drawable.ic_menu_rotate
            active.playWhenReady -> android.R.drawable.ic_media_pause
            else -> android.R.drawable.ic_media_play
        })
        play.contentDescription = when {
            ended -> "Putar ulang"
            active.playWhenReady -> "Jeda video"
            else -> "Putar video"
        }
        speed.text = "${speedLabel(active.playbackParameters.speed)}×"
        timeline.setDuration(duration())
        timeline.isEnabled = active.isCurrentMediaItemSeekable && duration() > 0
        if (!scrubbing) {
            timeline.setPosition(active.currentPosition)
            timeline.setBufferedPosition(active.bufferedPosition)
            time.text = "${clock(active.currentPosition)} / ${clock(duration())}"
        }
    }

    private fun duration(): Long = player?.duration?.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0

    private fun pause() {
        player?.pause()
        root.keepScreenOn = false
    }

    override fun getView(): View = root

    override fun dispose() {
        if (disposed) return
        disposed = true
        handler.removeCallbacksAndMessages(null)
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

    private fun textButton(value: String, description: String) = Button(activity).apply {
        text = value
        contentDescription = description
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = 14f
        backgroundTintList = ColorStateList.valueOf(Color.DKGRAY)
        minimumWidth = dp(48)
        minWidth = dp(48)
        setPadding(dp(4), 0, dp(4), 0)
    }

    private fun imageButton(icon: Int, description: String) = ImageButton(activity).apply {
        setImageResource(icon)
        setColorFilter(Color.WHITE)
        contentDescription = description
        backgroundTintList = ColorStateList.valueOf(Color.DKGRAY)
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }

    companion object {
        private val instances = Collections.newSetFromMap(IdentityHashMap<CachedPlayerView, Boolean>())

        @JvmStatic
        fun pauseAll() { instances.toList().forEach { it.pause() } }

        private fun speedLabel(speed: Float): String = speed.toString().removeSuffix(".0")

        private fun clock(milliseconds: Long): String {
            val seconds = milliseconds.coerceAtLeast(0) / 1000
            return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
            else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
        }
    }
}
