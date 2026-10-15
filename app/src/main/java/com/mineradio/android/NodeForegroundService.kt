package com.mineradio.android

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.webkit.WebView
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * 承载整个播放器的前台服务。
 *
 * 为什么把 WebView 放在 Service 而不是 Activity
 * --------------------------------------------
 * 音乐播放器必须能后台播放。如果 WebView 归 Activity 所有，用户一划掉最近任务，
 * Activity 被销毁、WebView 跟着没了，音乐立刻断。
 * 这里让 Service 持有 WebView（用带主题的 Service Context 创建），
 * Activity 只是「借用」它挂到自己的布局上；Activity 销毁时把 WebView 摘下来但不销毁，
 * 页面与音频继续存活，划掉任务后仍在播放。
 */
class NodeForegroundService : Service() {

    enum class Phase { IDLE, INSTALLING, STARTING_SERVER, LOADING_PAGE, READY, FAILED }

    inner class LocalBinder : Binder() {
        fun service(): NodeForegroundService = this@NodeForegroundService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mineradio-bootstrap").apply { isDaemon = true }
    }

    private var webView: WebView? = null
    private var playback: PlaybackController? = null
    private var started = false

    @Volatile
    var phase: Phase = Phase.IDLE
        private set

    @Volatile
    var statusText: String = ""
        private set

    @Volatile
    var failureReason: String = ""
        private set

    private val listeners = CopyOnWriteArrayList<(Phase, String) -> Unit>()
    private val playbackListeners = CopyOnWriteArrayList<(PlaybackState) -> Unit>()

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 必须在 5 秒内进入前台，先挂一个「启动中」通知
        goForeground(buildStatusNotification(getString(R.string.boot_starting)))

        when (intent?.action) {
            PlaybackController.ACTION_PLAY -> {
                evalJs("window.MineradioAndroid && window.MineradioAndroid.play()"); return START_STICKY
            }
            PlaybackController.ACTION_PAUSE -> {
                evalJs("window.MineradioAndroid && window.MineradioAndroid.pause()"); return START_STICKY
            }
            PlaybackController.ACTION_NEXT -> {
                evalJs("window.MineradioAndroid && window.MineradioAndroid.next()"); return START_STICKY
            }
            PlaybackController.ACTION_PREV -> {
                evalJs("window.MineradioAndroid && window.MineradioAndroid.previous()"); return START_STICKY
            }
        }

        bootstrap()
        // START_STICKY：被系统回收后自动拉起，配合已解压的资源可以秒起
        return START_STICKY
    }

    // ------------------------------------------------------------ 启动流程

    private fun bootstrap() {
        if (started) return
        started = true

        worker.execute {
            try {
                // 1) 解压运行时资源（仅首次或版本变化时）
                if (!AssetInstaller.isUpToDate(this)) {
                    setPhase(Phase.INSTALLING, getString(R.string.boot_installing))
                    AssetInstaller.install(this) { written, total, name ->
                        val pct = if (total > 0) (written * 100 / total).toInt() else 0
                        setPhase(Phase.INSTALLING, getString(R.string.boot_installing_pct, pct))
                        if (name.isNotEmpty() && pct % 20 == 0) Log.i(TAG, "解压中 $pct% $name")
                    }
                }

                // 2) 拉起内嵌 Node 服务
                setPhase(Phase.STARTING_SERVER, getString(R.string.boot_starting_server))
                val baseUrl = NodeRunner.start(this)
                if (baseUrl == null) {
                    fail(getString(R.string.boot_failed_server))
                    return@execute
                }

                // 3) 建 WebView 并加载（必须在主线程）
                setPhase(Phase.LOADING_PAGE, getString(R.string.boot_loading_page))
                mainHandler.post { createWebViewAndLoad(baseUrl) }

            } catch (t: Throwable) {
                Log.e(TAG, "启动流程异常", t)
                fail(t.message ?: getString(R.string.boot_failed_unknown))
            }
        }
    }

    private fun createWebViewAndLoad(baseUrl: String) {
        if (webView != null) return

        // WebView 需要主题上下文，否则部分控件样式取不到
        val themed = ContextThemeWrapper(this, R.style.Theme_Mineradio)

        val bridge = MediaBridge(
            onState = { state ->
                playback?.update(state)
                mainHandler.post { playbackListeners.forEach { it(state) } }
            },
            onLog = { msg -> Log.i("MineradioPage", msg) }
        )

        val controller = PlaybackController(themed) { script -> evalJs(script) }.apply {
            onNotificationInvalidated = { refreshNotification() }
            start()
        }
        playback = controller

        val view = MineradioWebViewFactory.create(
            context = themed,
            bridge = bridge,
            onPageReady = { setPhase(Phase.READY, "") },
            onLoadFailed = { desc -> fail(getString(R.string.boot_failed_page, desc)) }
        )

        webView = view

        // 首帧之前先把适配脚本插进去，保证挂钩子早于上游脚本执行
        view.evaluateJavascript(InjectedPageScript.EARLY, null)
        view.loadUrl(baseUrl)
        Log.i(TAG, "WebView 开始加载 $baseUrl")
    }

    private fun fail(reason: String) {
        failureReason = reason
        setPhase(Phase.FAILED, reason)
        Log.e(TAG, "启动失败：$reason")
    }

    private fun setPhase(next: Phase, text: String) {
        phase = next
        statusText = text
        mainHandler.post {
            listeners.forEach { it(next, text) }
            if (next != Phase.READY) goForeground(buildStatusNotification(text))
        }
    }

    // ------------------------------------------------------------ 对外接口

    fun addPhaseListener(listener: (Phase, String) -> Unit) {
        listeners.add(listener)
        mainHandler.post { listener(phase, statusText) }
    }

    fun removePhaseListener(listener: (Phase, String) -> Unit) {
        listeners.remove(listener)
    }

    /** 播放状态变化（用于「播放时保持屏幕常亮」这类界面联动） */
    fun addPlaybackListener(listener: (PlaybackState) -> Unit) {
        playbackListeners.add(listener)
    }

    fun removePlaybackListener(listener: (PlaybackState) -> Unit) {
        playbackListeners.remove(listener)
    }

    /** 拿到 WebView 的所有权（由 Activity 挂载到界面上） */
    fun obtainWebView(): WebView? = webView

    fun retry() {
        started = false
        phase = Phase.IDLE
        NodeRunner.reset()
        bootstrap()
    }

    fun reloadPage() {
        webView?.reload()
    }

    private fun evalJs(script: String) {
        mainHandler.post {
            runCatching { webView?.evaluateJavascript(script, null) }
                .onFailure { Log.w(TAG, "执行 JS 失败", it) }
        }
    }

    // ------------------------------------------------------------ 通知

    private fun goForeground(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "进入前台失败", t)
        }
    }

    private fun refreshNotification() {
        val controller = playback ?: return
        val notification = controller.buildNotification(
            getString(R.string.notif_idle_title),
            getString(R.string.notif_idle_text)
        )
        runCatching {
            getSystemService(android.app.NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, notification)
        }.onFailure { Log.w(TAG, "刷新通知失败", it) }
    }

    private fun buildStatusNotification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, MineradioApp.CHANNEL_PLAYBACK)
            .setSmallIcon(R.drawable.ic_mineradio_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(phase != Phase.FAILED)
            .build()
    }

    // ------------------------------------------------------------ 生命周期

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 刻意不 stopSelf：WebView 归服务所有，划掉任务后音乐应当继续。
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.i(TAG, "服务销毁")
        listeners.clear()
        playbackListeners.clear()
        playback?.release()
        playback = null
        webView?.let { view ->
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            runCatching { view.destroy() }
        }
        webView = null
        worker.shutdownNow()
        instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MineradioService"
        private const val NOTIFICATION_ID = 4211

        @Volatile
        var instance: NodeForegroundService? = null
            private set

        /** 幂等启动：Activity 和开机自启都走这里 */
        fun start(context: Context) {
            val intent = Intent(context, NodeForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
