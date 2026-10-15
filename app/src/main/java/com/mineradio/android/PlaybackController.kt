package com.mineradio.android

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState as FrameworkPlaybackState
import android.util.Log
import android.util.LruCache
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 通知栏 / 锁屏 / 蓝牙耳机按键的播放控制。
 *
 * 页面里的音频是 HTML5 `<audio>`，本身不会产生系统级媒体通知。
 * 这里用注入脚本上报的状态驱动一个真正的 MediaSession，
 * 再把控制指令回传给页面 —— 这样在手机上才「像个播放器」。
 */
class PlaybackController(
    private val context: Context,
    /** 把 JS 片段交给 WebView 执行 */
    private val runScript: (String) -> Unit
) {

    private val session: MediaSession = MediaSession(context, "Mineradio")
    private val coverCache = LruCache<String, Bitmap>(6)
    private val coverExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mineradio-cover").apply { isDaemon = true }
    }

    @Volatile
    private var state = PlaybackState()

    @Volatile
    private var coverBitmap: Bitmap? = null

    /** 状态变化时通知外部刷新通知栏 */
    var onNotificationInvalidated: (() -> Unit)? = null

    fun start() {
        session.setFlags(
            MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
        )

        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = js("window.MineradioAndroid && window.MineradioAndroid.play()")
            override fun onPause() = js("window.MineradioAndroid && window.MineradioAndroid.pause()")
            override fun onStop() = js("window.MineradioAndroid && window.MineradioAndroid.pause()")
            override fun onSkipToNext() = js("window.MineradioAndroid && window.MineradioAndroid.next()")
            override fun onSkipToPrevious() = js("window.MineradioAndroid && window.MineradioAndroid.previous()")

            override fun onSeekTo(pos: Long) =
                js("window.MineradioAndroid && window.MineradioAndroid.seekTo(${pos / 1000.0})")

            override fun onPlayFromMediaId(mediaId: String?, extras: android.os.Bundle?) =
                js("window.MineradioAndroid && window.MineradioAndroid.play()")
        })

        session.isActive = true
    }

    fun update(newState: PlaybackState) {
        val titleChanged = newState.title != state.title || newState.cover != state.cover
        state = newState

        if (titleChanged) {
            loadCoverAsync(newState.cover)
        }
        pushSessionState()
        onNotificationInvalidated?.invoke()
    }

    /** 供前台服务构建通知 */
    fun buildNotification(fallbackTitle: String, fallbackText: String): Notification {
        val playing = state.playing
        val title = state.displayTitle(fallbackTitle)
        val text = state.artist.ifBlank { fallbackText }

        val contentIntent = PendingIntent.getActivity(
            context,
            REQ_CONTENT,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = Notification.Builder(context, MineradioApp.CHANNEL_PLAYBACK)
            .setSmallIcon(R.drawable.ic_mineradio_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(playing)
            .addAction(
                Notification.Action.Builder(
                    null, context.getString(R.string.action_previous),
                    servicePendingIntent(ACTION_PREV, REQ_PREV)
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    null,
                    context.getString(if (playing) R.string.action_pause else R.string.action_play),
                    servicePendingIntent(if (playing) ACTION_PAUSE else ACTION_PLAY, REQ_TOGGLE)
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    null, context.getString(R.string.action_next),
                    servicePendingIntent(ACTION_NEXT, REQ_NEXT)
                ).build()
            )
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )

        coverBitmap?.let { builder.setLargeIcon(it) }
        return builder.build()
    }

    fun release() {
        runCatching {
            session.isActive = false
            session.release()
        }
        coverExecutor.shutdownNow()
    }

    // ------------------------------------------------------------ 内部

    /**
     * 注意返回类型必须是 Unit：MediaSession.Callback 的各回调都返回 Unit，
     * 若这里返回 Result 会导致 override 的类型不匹配。
     */
    private fun js(script: String) {
        runCatching { runScript(script) }
            .onFailure { Log.w(TAG, "执行 JS 失败：$script", it) }
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            context,
            requestCode,
            Intent(context, NodeForegroundService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun pushSessionState() {
        val durationMs = (state.duration * 1000).toLong().coerceAtLeast(0L)
        val positionMs = (state.position * 1000).toLong().coerceAtLeast(0L)

        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, state.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, state.artist)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
            .apply { coverBitmap?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
            .build()
        session.setMetadata(metadata)

        val playbackState = FrameworkPlaybackState.Builder()
            .setActions(
                FrameworkPlaybackState.ACTION_PLAY or
                    FrameworkPlaybackState.ACTION_PAUSE or
                    FrameworkPlaybackState.ACTION_PLAY_PAUSE or
                    FrameworkPlaybackState.ACTION_SKIP_TO_NEXT or
                    FrameworkPlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    FrameworkPlaybackState.ACTION_SEEK_TO
            )
            .setState(
                if (state.playing) FrameworkPlaybackState.STATE_PLAYING
                else FrameworkPlaybackState.STATE_PAUSED,
                positionMs,
                if (state.playing) 1.0f else 0.0f
            )
            .build()
        session.setPlaybackState(playbackState)
    }

    /**
     * 异步取封面。取不到就退回默认图标 —— 通知栏绝不能因为一张图卡住。
     */
    private fun loadCoverAsync(url: String) {
        if (url.isEmpty()) {
            coverBitmap = null
            onNotificationInvalidated?.invoke()
            return
        }

        coverCache.get(url)?.let {
            coverBitmap = it
            onNotificationInvalidated?.invoke()
            return
        }

        coverExecutor.execute {
            val bitmap = download(url)
            if (bitmap != null) {
                coverCache.put(url, bitmap)
                // 期间用户可能已经切歌，只有 URL 仍然匹配才应用
                if (state.cover == url) {
                    coverBitmap = bitmap
                    pushSessionState()
                    onNotificationInvalidated?.invoke()
                }
            }
        }
    }

    private fun download(url: String): Bitmap? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 10000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
        }
        conn.inputStream.use { input ->
            BitmapFactory.decodeStream(input)
        }.also { conn.disconnect() }
    } catch (t: Throwable) {
        Log.w(TAG, "封面加载失败：$url", t)
        null
    }

    companion object {
        private const val TAG = "MineradioPlayback"
        private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Mineradio"

        const val ACTION_PLAY = "com.mineradio.android.action.PLAY"
        const val ACTION_PAUSE = "com.mineradio.android.action.PAUSE"
        const val ACTION_NEXT = "com.mineradio.android.action.NEXT"
        const val ACTION_PREV = "com.mineradio.android.action.PREV"

        private const val REQ_CONTENT = 100
        private const val REQ_TOGGLE = 101
        private const val REQ_NEXT = 102
        private const val REQ_PREV = 103
    }
}
