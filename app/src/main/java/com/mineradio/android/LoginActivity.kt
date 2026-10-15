package com.mineradio.android

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/**
 * 各音乐平台的官方网页登录窗口。
 *
 * 桌面版的做法是弹一个 Electron BrowserWindow 加载官方登录页，然后从 session
 * 里捞 cookie。安卓端没有 Electron，这里用原生 WebView 做等价的事：
 *
 *   1. 加载官方登录页，用户正常扫码 / 输账号
 *   2. 每 1.2 秒读一次 CookieManager，用与桌面版**完全相同**的条件判断是否登录成功
 *   3. 成功后把 cookie 拼成 Cookie 头，交回网页层（网页层再 POST 到 /api/login/cookie）
 *
 * 这样「电脑上能登的，手机上也能登」，而且不需要第二台设备来扫码。
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var provider: LoginProvider
    private lateinit var requestId: String

    private var webView: WebView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var settled = false
    private var statusView: TextView? = null

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (settled) return
            checkCookie(auto = true)
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        provider = LoginProvider.from(intent.getStringExtra(EXTRA_PROVIDER))
            ?: run { finish(); return }
        requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: ""

        Log.i(TAG, "打开登录窗口: ${provider.id} requestId=$requestId")
        setContentView(buildUi())
        setupWebView()
    }

    // ------------------------------------------------------------ 界面

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FF05060A"))
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.parseColor("#FF0B0E16"))
        }

        val title = TextView(this).apply {
            text = "登录${provider.displayName}"
            setTextColor(Color.parseColor("#FFE8EDF6"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val status = TextView(this).apply {
            text = "登录后会自动返回"
            setTextColor(Color.parseColor("#FF8A94A6"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        statusView = status

        val doneBtn = Button(this).apply {
            text = "完成登录"
            setTextColor(Color.parseColor("#FF05060A"))
            setBackgroundColor(Color.parseColor("#FF6EE7F0"))
            setOnClickListener { checkCookie(auto = false) }
        }

        val closeBtn = Button(this).apply {
            text = "关闭"
            setTextColor(Color.parseColor("#FF8A94A6"))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { finishWith(null) }
        }

        bar.addView(title)
        bar.addView(doneBtn)
        bar.addView(closeBtn)
        root.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val statusRow = LinearLayout(this).apply {
            setPadding(dp(16), 0, dp(16), dp(8))
            setBackgroundColor(Color.parseColor("#FF0B0E16"))
            addView(status)
        }
        root.addView(statusRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val wv = WebView(this)
        webView = wv
        root.addView(wv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        return root
    }

    // ------------------------------------------------------------ WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val wv = webView ?: return

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = wv.settings.userAgentString
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                Log.i(TAG, "登录页加载完成: $url")
                // 与桌面版一致：自动点一下页面上的「登录」入口
                view.evaluateJavascript(AUTO_CLICK_SCRIPT, null)
                checkCookie(auto = true)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                // 非 http(s) 一律交给系统（如唤起 App）
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    return true
                }
                return false
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            // QQ / 酷狗登录可能弹新窗口，收编到同一个 WebView 里
            override fun onCreateWindow(
                view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?
            ): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val popup = WebView(this@LoginActivity)
                popup.settings.javaScriptEnabled = true
                popup.settings.domStorageEnabled = true
                popup.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(v: WebView, url: String) {
                        v.evaluateJavascript(AUTO_CLICK_SCRIPT, null)
                        checkCookie(auto = true)
                    }
                }
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }
        }

        provider.warmupUrl?.let { warmup ->
            // 先预热一次，让站点把必要的 cookie 种下来
            wv.loadUrl(warmup)
            handler.postDelayed({ if (!settled) wv.loadUrl(provider.loginUrl) }, 1200)
        } ?: wv.loadUrl(provider.loginUrl)

        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    // ------------------------------------------------------------ Cookie

    private fun collectCookie(): String {
        val manager = CookieManager.getInstance()
        val merged = LinkedHashMap<String, String>()
        provider.cookieUrls.forEach { url ->
            val header = runCatching { manager.getCookie(url) }.getOrNull()
            LoginCookieInspector.parse(header).forEach { (k, v) ->
                if (!merged.containsKey(k)) merged[k] = v
            }
        }
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /**
     * @param auto true 表示轮询触发（只在确认登录成功时才结束），
     *             false 表示用户点了「完成登录」（有 cookie 就返回）
     */
    private fun checkCookie(auto: Boolean) {
        if (settled) return
        val cookie = collectCookie()
        val ok = LoginCookieInspector.hasLogin(provider, cookie)

        if (ok) {
            Log.i(TAG, "检测到登录成功，cookie 长度=${cookie.length}")
            statusView?.text = "登录成功，正在返回…"
            finishWith(cookie)
            return
        }

        if (!auto) {
            if (cookie.isNotBlank()) {
                // 用户主动点了完成，交给服务端判定（可能平台判定条件更宽）
                statusView?.text = "已获取登录信息，正在返回…"
                finishWith(cookie)
            } else {
                statusView?.text = "还没拿到登录信息，请先在页面上完成登录"
            }
        }
    }

    private fun finishWith(cookie: String?) {
        if (settled) return
        settled = true
        handler.removeCallbacksAndMessages(null)

        val json = JSONObject().apply {
            if (cookie.isNullOrBlank()) {
                put("ok", false)
                put("cancelled", true)
                put("message", "已取消登录")
            } else {
                put("ok", true)
                put("cookie", cookie)
                val nick = LoginCookieInspector.nickname(provider, cookie)
                if (nick.isNotEmpty()) put("nickname", nick)
            }
        }.toString()

        LoginBridge.deliver(requestId, json)
        finish()
    }

    override fun onBackPressed() {
        finishWith(null)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        webView?.let { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
            runCatching { wv.destroy() }
        }
        webView = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MineradioLogin"
        private const val POLL_INTERVAL_MS = 1200L

        const val EXTRA_PROVIDER = "provider"
        const val EXTRA_REQUEST_ID = "request_id"

        /** 与桌面版 desktop/main.js 里的自动点击逻辑保持一致 */
        private val AUTO_CLICK_SCRIPT = """
(function () {
  try {
    var docs = [document];
    document.querySelectorAll('iframe').forEach(function (f) {
      try { if (f.contentDocument) docs.push(f.contentDocument); } catch (e) {}
    });
    for (var d = 0; d < docs.length; d++) {
      var nodes = Array.prototype.slice.call(
        docs[d].querySelectorAll('a, button, span, div'));
      for (var i = 0; i < nodes.length; i++) {
        var node = nodes[i];
        var text = (node.textContent || '').trim();
        if (!/登录|立即登录/.test(text)) continue;
        var rect = node.getBoundingClientRect();
        if (rect.width > 0 && rect.height > 0) { node.click(); return true; }
      }
    }
  } catch (e) {}
  return false;
})()
""".trimIndent()
    }
}
