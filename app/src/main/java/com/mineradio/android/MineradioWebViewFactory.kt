package com.mineradio.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * 构建承载 Mineradio 前端的 WebView。
 *
 * 性能相关的设置都是为了「丝滑」这两个字：
 *   - 硬件加速 + 离屏预栅格化，滚动/转场不掉帧
 *   - 关闭缩放，避免误触双指缩放把版式搞乱
 *   - textZoom 固定 100，防止系统字体放大把桌面版式挤爆
 *   - 开启 DOM Storage，上游把大量用户偏好存在 localStorage 里
 */
object MineradioWebViewFactory {

    private const val TAG = "MineradioWebView"

    /** 与本地服务通信的地址判定 */
    fun isLocalUrl(url: String?): Boolean {
        if (url == null) return false
        return url.startsWith("http://127.0.0.1") ||
            url.startsWith("http://localhost") ||
            url.startsWith("https://127.0.0.1")
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun create(
        context: Context,
        bridge: MediaBridge,
        onPageReady: (String) -> Unit,
        onLoadFailed: (String) -> Unit
    ): WebView {
        val webView = WebView(context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)

            // 播放由用户点击触发，但上游会在曲目切换时自动续播，不能要求每次都有手势
            mediaPlaybackRequiresUserGesture = false

            // 桌面版式靠 viewport 宽度，缩放只会添乱
            useWideViewPort = true
            loadWithOverviewMode = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false

            // 系统字体放大会把桌面版式挤爆，这里锁死
            textZoom = 100

            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            // 本地服务是自己人，不需要文件/内容提供器访问
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)

            // 渲染优先级与离屏预栅格化：滚动与视觉动效更顺
            setRenderPriority(WebSettings.RenderPriority.HIGH)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setOffscreenPreRaster(true)
            }
        }

        // 第三方音源登录要存 Cookie，必须开启
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.addJavascriptInterface(bridge, MediaBridge.NAME)

        // ------------------------------------------------------------------
        // 适配脚本注入
        //
        // EARLY 必须在**文档开始解析之前**执行（要抢在上游脚本前面装好
        // window.MineradioAndroid / desktopWindow 和媒体钩子）。
        // 早先的写法是在 loadUrl 之前调用 evaluateJavascript，那样脚本会跑在
        // about:blank 上，页面一加载就丢了。
        //
        // 正确做法是 DOCUMENT_START_SCRIPT（WebView 89+，AndroidX WebKit 提供），
        // 不可用时退回到 onPageStarted 注入 —— 此时文档上下文已经建立，
        // 注入的全局对象能存活到页面脚本执行。
        // ------------------------------------------------------------------
        val documentStartSupported =
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

        if (documentStartSupported) {
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(
                    webView, InjectedPageScript.EARLY, setOf("*")
                )
                Log.i(TAG, "已注册文档级注入（DOCUMENT_START_SCRIPT）")
            }.onFailure {
                Log.w(TAG, "文档级注入注册失败，回退到 onPageStarted", it)
            }
        }

        webView.webViewClient = object : WebViewClient() {

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                Log.i(TAG, "开始加载：$url")
                if (!documentStartSupported && isLocalUrl(url)) {
                    // 回退路径：此时新文档的 JS 上下文已建立，注入的全局对象会保留
                    view.evaluateJavascript(InjectedPageScript.EARLY, null)
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                if (isLocalUrl(url)) return false

                // 非本地地址一律丢给系统浏览器，避免在播放器里跑出一个残缺网页
                return openExternally(view.context, url)
            }

            @Deprecated("兼容旧实现")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                if (isLocalUrl(url)) return false
                return openExternally(view.context, url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                Log.i(TAG, "加载完成：$url")
                if (isLocalUrl(url)) {
                    view.evaluateJavascript(InjectedPageScript.LATE, null)
                    onPageReady(url)
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (!request.isForMainFrame) return
                val desc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    "${error.errorCode} ${error.description}"
                } else {
                    "加载失败"
                }
                Log.e(TAG, "主文档加载失败：$desc url=${request.url}")
                onLoadFailed(desc)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                // 把前端 console 转到 logcat，真机上排查问题全靠它
                Log.i(
                    "MineradioConsole",
                    "${msg.message()} @${msg.sourceId()}:${msg.lineNumber()}"
                )
                return true
            }

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (newProgress == 100) {
                    Log.i(TAG, "页面进度 100%")
                }
            }
        }

        // 让 WebView 能拿到音频焦点；同时避免过度绘制背景闪烁
        webView.setBackgroundColor(0xFF05060A.toInt())
        webView.overScrollMode = View.OVER_SCROLL_NEVER

        return webView
    }

    private fun openExternally(context: Context, url: String): Boolean = try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        true
    } catch (t: Throwable) {
        Log.w(TAG, "无法打开外部链接：$url", t)
        false
    }
}
