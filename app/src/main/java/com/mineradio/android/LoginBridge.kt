package com.mineradio.android

import java.util.concurrent.ConcurrentHashMap

/**
 * 登录请求的挂起表。
 *
 * 网页层的 `desktopWindow.openXxxMusicLogin()` 需要返回一个 Promise，
 * 而 Android 的 `@JavascriptInterface` 方法无法直接返回 Promise。
 * 所以走「请求-回调」：
 *
 *   网页 → MineradioNative.openLogin(provider, requestId)   （发起）
 *   原生 → 打开 LoginActivity，用户登录
 *   原生 → window.__mineradioLoginResult(requestId, json)   （回调，网页层据此 resolve）
 */
object LoginBridge {

    private val pending = ConcurrentHashMap<String, (String) -> Unit>()

    /** 登记一个待完成的请求；同 id 重复登记会覆盖 */
    fun await(requestId: String, deliver: (String) -> Unit) {
        pending[requestId] = deliver
    }

    /** LoginActivity 拿到结果后调用 */
    fun deliver(requestId: String, json: String) {
        val cb = pending.remove(requestId) ?: return
        runCatching { cb(json) }
    }

    fun cancel(requestId: String) {
        pending.remove(requestId)
    }

    fun cancelAll() {
        pending.clear()
    }
}
