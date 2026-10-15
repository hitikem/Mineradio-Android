package com.mineradio.android

import android.util.Log
import android.webkit.JavascriptInterface
import org.json.JSONObject

/** 页面侧上报的播放状态 */
data class PlaybackState(
    val hasMedia: Boolean = false,
    val playing: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val cover: String = "",
    val duration: Double = 0.0,
    val position: Double = 0.0
) {
    val isEmpty: Boolean get() = title.isEmpty() && artist.isEmpty() && !hasMedia

    /** 通知栏标题：没歌时给个中性文案，别显示空白 */
    fun displayTitle(fallback: String): String = title.ifBlank { fallback }

    companion object {
        fun fromJson(json: String): PlaybackState? = try {
            val o = JSONObject(json)
            PlaybackState(
                hasMedia = o.optBoolean("hasMedia", false),
                playing = o.optBoolean("playing", false),
                title = o.optString("title", "").trim(),
                artist = o.optString("artist", "").trim(),
                cover = o.optString("cover", "").trim(),
                duration = o.optDouble("duration", 0.0),
                position = o.optDouble("position", 0.0)
            )
        } catch (t: Throwable) {
            Log.w(MediaBridge.TAG, "解析播放状态失败：$json", t)
            null
        }
    }
}

/**
 * 暴露给网页的 JS 桥（`window.MineradioNative`）。
 *
 * 所有被 `@JavascriptInterface` 标注的方法都会在 WebView 的 JavaBridge 线程上调用，
 * 因此这里只做「转交」，具体工作交给上层处理。
 */
class MediaBridge(
    private val onState: (PlaybackState) -> Unit,
    private val onLog: (String) -> Unit,
    private val onOpenLogin: (LoginProvider, String) -> Unit
) {

    @JavascriptInterface
    fun onState(json: String) {
        PlaybackState.fromJson(json)?.let(onState)
    }

    @JavascriptInterface
    fun log(message: String) {
        onLog(message)
    }

    /**
     * 打开官方网页登录窗口。
     * 结果通过 `window.__mineradioLoginResult(requestId, json)` 异步回传。
     */
    @JavascriptInterface
    fun openLogin(providerId: String, requestId: String) {
        val provider = LoginProvider.from(providerId)
        if (provider == null) {
            LoginBridge.deliver(
                requestId,
                """{"ok":false,"error":"UNSUPPORTED_PROVIDER","message":"暂不支持该平台"}"""
            )
            return
        }
        onOpenLogin(provider, requestId)
    }

    companion object {
        const val TAG = "MineradioMediaBridge"
        const val NAME = "MineradioNative"
    }
}
