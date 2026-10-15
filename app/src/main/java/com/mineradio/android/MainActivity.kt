package com.mineradio.android

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.mineradio.android.databinding.ActivityMainBinding

/**
 * 播放器界面宿主。
 *
 * 这里刻意做得很薄：真正的运行时（Node + WebView）都归 [NodeForegroundService] 所有，
 * Activity 只负责把 WebView 挂到界面上、显示启动进度、处理返回键与权限。
 * 这样无论是转屏、切后台还是从最近任务划掉，播放都不会因为 Activity 的生死而中断。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var service: NodeForegroundService? = null
    private var bound = false
    private var attachedWebView: WebView? = null
    private var pageReady = false

    private val phaseListener: (NodeForegroundService.Phase, String) -> Unit = { phase, text ->
        renderPhase(phase, text)
    }

    private val playbackListener: (PlaybackState) -> Unit = { state ->
        // 播放时保持屏幕常亮（可视化场景下符合预期），暂停即释放
        if (state.playing) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = (binder as? NodeForegroundService.LocalBinder)?.service() ?: return
            service = local
            bound = true

            local.addPhaseListener(phaseListener)
            local.addPlaybackListener(playbackListener)
            renderPhase(local.phase, local.statusText)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        BootLog.i(TAG, "MainActivity.onCreate（版本 ${BuildConfig.VERSION_NAME}）")

        // 版本号常显在启动遮罩上：用户一眼就能确认装的是哪个包，
        // 避免「改了包但手机上还是旧版本」这种白忙一场的情况
        binding.bootVersion.text =
            getString(R.string.boot_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)

        binding.bootRetry.setOnClickListener {
            BootLog.i(TAG, "用户点了重试")
            pageReady = false
            binding.bootRetry.visibility = View.GONE
            binding.bootHint.visibility = View.GONE
            binding.bootViewLog.visibility = View.GONE
            service?.retry()
        }

        binding.bootViewLog.setOnClickListener {
            LogViewerActivity.start(this)
        }

        // 长按版本号也能进日志页：启动卡住、连失败提示都没出现时的兜底入口
        binding.bootVersion.setOnLongClickListener {
            LogViewerActivity.start(this)
            true
        }

        setupBackHandling()
        requestNotificationPermission()

        // 先起服务，再绑定：服务里会立刻进入前台，避免后台被回收
        NodeForegroundService.start(this)
    }

    override fun onStart() {
        super.onStart()
        bindService(
            Intent(this, NodeForegroundService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onStop() {
        if (bound) {
            service?.removePhaseListener(phaseListener)
            service?.removePlaybackListener(playbackListener)
            unbindService(connection)
            bound = false
            service = null
        }
        super.onStop()
    }

    override fun onDestroy() {
        // 只把 WebView 从界面上摘下来，**不销毁**：它归服务所有，摘下来之后音乐继续放
        detachWebView()
        super.onDestroy()
    }

    // ------------------------------------------------------------ 界面状态

    private fun renderPhase(phase: NodeForegroundService.Phase, text: String) {
        when (phase) {
            NodeForegroundService.Phase.READY -> {
                pageReady = true
                attachWebView()
                hideOverlay()
            }
            NodeForegroundService.Phase.FAILED -> {
                // 失败时把服务里记录的详细原因也写进日志，界面上只留一句人话
                BootLog.e(TAG, "界面进入失败态：$text")
                showOverlay(text)
                binding.bootRetry.visibility = View.VISIBLE
                binding.bootViewLog.visibility = View.VISIBLE
                binding.bootHint.visibility = View.VISIBLE
            }
            else -> showOverlay(text)
        }
    }

    private fun showOverlay(text: String) {
        binding.bootOverlay.visibility = View.VISIBLE
        binding.bootStatus.text = text.ifBlank { getString(R.string.boot_starting) }
        binding.bootRetry.visibility = View.GONE
        binding.bootViewLog.visibility = View.GONE
        binding.bootHint.visibility = View.GONE
    }

    private fun hideOverlay() {
        binding.bootOverlay.animate()
            .alpha(0f)
            .setDuration(260)
            .withEndAction { binding.bootOverlay.visibility = View.GONE }
            .start()
    }

    private fun attachWebView() {
        val view = service?.obtainWebView() ?: return
        if (attachedWebView === view && view.parent === binding.webviewContainer) return

        detachWebView()

        (view.parent as? ViewGroup)?.removeView(view)
        view.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        binding.webviewContainer.addView(view)
        attachedWebView = view
        Log.i(TAG, "WebView 已挂载到界面")
    }

    private fun detachWebView() {
        attachedWebView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
        }
        attachedWebView = null
    }

    // ------------------------------------------------------------ 返回键

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val view = attachedWebView
                if (view == null) {
                    moveTaskToBack(true)
                    return
                }

                // 先问页面：能不能关掉一个弹层？关掉了就留在应用里
                view.evaluateJavascript(
                    "(function(){try{return (window.MineradioAndroid && window.MineradioAndroid.back) ? window.MineradioAndroid.back() : 'ignored';}catch(e){return 'ignored';}})()"
                ) { result ->
                    val handled = result?.contains("handled") == true
                    if (handled) return@evaluateJavascript

                    if (view.canGoBack()) {
                        view.goBack()
                    } else {
                        // 没有可关的弹层：退到后台而不是退出，音乐继续播放
                        moveTaskToBack(true)
                    }
                }
            }
        })
    }

    // ------------------------------------------------------------ 权限

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            // 拒绝也不影响播放，只是看不到通知栏控件
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION)
        }
    }

    companion object {
        private const val TAG = "MineradioActivity"
        private const val REQ_NOTIFICATION = 9001
    }
}
