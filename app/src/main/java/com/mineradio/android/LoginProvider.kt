package com.mineradio.android

/**
 * 各音乐平台的登录参数与「登录成功」判定。
 *
 * 这些判定条件**直接照搬桌面版 desktop/main.js**（neteaseCookieHasLogin /
 * qqCookieHasPlaybackLogin / extractKugouAuth），保证安卓端与电脑端
 * 认可同一套登录态，不会出现「电脑能登、手机登了却不认」的情况。
 */
enum class LoginProvider(
    val id: String,
    val displayName: String,
    /** 打开这个页面让用户登录 */
    val loginUrl: String,
    /** 先访问一下这个地址，让站点把 cookie 种下来（酷狗/QQ 需要） */
    val warmupUrl: String?,
    /** 从这些地址收集 cookie，合并成一个 Cookie 头 */
    val cookieUrls: List<String>
) {
    NETEASE(
        id = "netease",
        displayName = "网易云音乐",
        loginUrl = "https://music.163.com/#/login",
        warmupUrl = null,
        cookieUrls = listOf("https://music.163.com", "https://music.163.com/")
    ),

    QQ(
        id = "qq",
        displayName = "QQ 音乐",
        loginUrl = "https://y.qq.com/n/ryqq/profile",
        warmupUrl = "https://y.qq.com/",
        cookieUrls = listOf(
            "https://y.qq.com/",
            "https://y.qq.com/n/ryqq/profile",
            "https://u.y.qq.com/",
            "https://c.y.qq.com/",
            "https://graph.qq.com/",
            "https://music.qq.com/",
            "https://qq.com/"
        )
    ),

    KUGOU(
        id = "kugou",
        displayName = "酷狗音乐",
        loginUrl = "https://www.kugou.com/",
        warmupUrl = "https://www.kugou.com/newuc/user/uc/type=edit",
        cookieUrls = listOf(
            "https://www.kugou.com/",
            "https://login-user.kugou.com/",
            "https://kugou.com/"
        )
    );

    companion object {
        fun from(id: String?): LoginProvider? =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

/** 判断拿到的 Cookie 头是否代表「已登录」 */
object LoginCookieInspector {

    /** 把 Cookie 头解析成键值对（同名取第一个，与上游一致） */
    fun parse(header: String?): Map<String, String> {
        if (header.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        header.split(';').forEach { part ->
            val i = part.indexOf('=')
            if (i <= 0) return@forEach
            val k = part.substring(0, i).trim()
            val v = part.substring(i + 1).trim()
            if (k.isNotEmpty() && !out.containsKey(k)) out[k] = v
        }
        return out
    }

    fun hasLogin(provider: LoginProvider, header: String?): Boolean {
        val c = parse(header)
        return when (provider) {
            // desktop: neteaseCookieHasLogin —— 只认 MUSIC_U
            LoginProvider.NETEASE -> c["MUSIC_U"].isNullOrEmpty().not()

            // desktop: qqCookieHasPlaybackLogin —— 要有数字 uin 且有播放密钥
            LoginProvider.QQ -> {
                val rawUin = firstNonEmpty(
                    c["uin"], c["qqmusic_uin"], c["wxuin"], c["p_uin"]
                )
                val uin = rawUin.orEmpty().filter { it.isDigit() }
                val key = firstNonEmpty(
                    c["qm_keyst"], c["qqmusic_key"], c["music_key"], c["wxskey"]
                )
                uin.isNotEmpty() && !key.isNullOrEmpty()
            }

            // desktop: extractKugouAuth —— 要有数字 userid 且有 token
            LoginProvider.KUGOU -> {
                val rawId = firstNonEmpty(
                    c["userid"], c["UserId"], c["KugooID"], c["kugouID"]
                )
                val uid = rawId.orEmpty().filter { it.isDigit() }
                val token = firstNonEmpty(c["token"], c["Token"], c["t"], c["T"])
                uid.isNotEmpty() && !token.isNullOrEmpty()
            }
        }
    }

    /** 取昵称用于提示（拿不到就返回空串） */
    fun nickname(provider: LoginProvider, header: String?): String {
        val c = parse(header)
        val raw = when (provider) {
            LoginProvider.NETEASE -> firstNonEmpty(c["__nickname"], c["_ntes_nn"], c["nickname"])
            LoginProvider.QQ -> firstNonEmpty(c["nickname"], c["qq_nickname"], c["ptnick"])
            LoginProvider.KUGOU -> firstNonEmpty(c["NickName"], c["nickname"], c["UserName"])
        } ?: return ""
        return runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
    }

    private fun firstNonEmpty(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }
}
