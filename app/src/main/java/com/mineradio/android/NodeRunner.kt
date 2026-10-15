package com.mineradio.android

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL

/**
 * 内嵌 Node 运行时的启动与就绪探测。
 *
 * 运行模型：Node 通过 JNI 在**应用进程内**的一个独立线程里跑（nodejs-mobile 的 libnode.so）。
 * 它监听 127.0.0.1 的一个随机空闲端口，WebView 直接加载该地址，
 * 于是上游那套「Electron 主进程起本地 HTTP 服务 + 渲染进程加载」的结构在安卓上原样成立。
 */
object NodeRunner {

    private const val TAG = "MineradioNodeRunner"

    @Volatile
    private var started = false

    @Volatile
    var port: Int = 0
        private set

    private var loadError: Throwable? = null

    // ------------------------------------------------------------ JNI

    private external fun nativeStartNode(
        projectDir: String,
        entryScript: String,
        envPairs: Array<String>,
        nodeArgs: Array<String>
    ): Boolean

    private external fun nativeIsRunning(): Boolean

    private external fun nativeExitCode(): Int

    init {
        try {
            // 顺序不能反：libnode.so 必须先加载，mineradio_node.so 依赖它
            System.loadLibrary("node")
            System.loadLibrary("mineradio_node")
            Log.i(TAG, "原生库加载成功")
        } catch (t: Throwable) {
            loadError = t
            Log.e(TAG, "原生库加载失败", t)
        }
    }

    val isNativeAvailable: Boolean get() = loadError == null

    val isRunning: Boolean
        get() = started && loadError == null && runCatching { nativeIsRunning() }.getOrDefault(false)

    // ------------------------------------------------------------ 启动

    /**
     * 启动内嵌服务。会阻塞到本地 HTTP 服务可访问为止。
     *
     * @return 本地服务地址（形如 http://127.0.0.1:17321），失败返回 null
     */
    fun start(context: Context): String? {
        if (started) {
            return if (port > 0) "http://127.0.0.1:$port" else null
        }

        loadError?.let {
            Log.e(TAG, "原生库不可用，无法启动", it)
            return null
        }

        val projectDir = AppConfig.nodeAppDir(context)
        val entry = File(projectDir, AppConfig.NODE_ENTRY)
        if (!entry.isFile()) {
            Log.e(TAG, "入口脚本不存在：${entry.absolutePath}")
            return null
        }

        val chosenPort = findFreePort()
        if (chosenPort <= 0) {
            Log.e(TAG, "找不到可用端口")
            return null
        }

        val userData = AppConfig.userDataDir(context).apply { mkdirs() }
        val logDir = AppConfig.logDir(context).apply { mkdirs() }

        val env = arrayOf(
            // 上游 server.js 直接读这两个变量
            "PORT=$chosenPort",
            "HOST=127.0.0.1",
            // 桌面端原本把数据放 app.getPath('userData')，这里换成应用私有目录
            "MINERADIO_ANDROID_USER_DATA=${userData.absolutePath}",
            "MINERADIO_PROJECT_DIR=${projectDir.absolutePath}",
            "MINERADIO_VERSION=2.2.0-android.1",
            "NODE_ENV=production",
            // 让 Node 与依赖库把缓存、临时文件都写在私有目录里
            "HOME=${userData.absolutePath}",
            "TMPDIR=${File(userData, "tmp").apply { mkdirs() }.absolutePath}",
            "XDG_CACHE_HOME=${File(userData, "cache").apply { mkdirs() }.absolutePath}",
            "NODE_PATH=${File(projectDir, "node_modules").absolutePath}",
            "MINERADIO_LOG_DIR=${logDir.absolutePath}"
        )

        val nodeArgs = arrayOf(
            // 手机内存有限，显式限制堆，避免被系统 OOM Killer 干掉
            "--max-old-space-size=${AppConfig.NODE_MAX_OLD_SPACE_MB}",
            "--max-semi-space-size=16"
        )

        Log.i(TAG, "启动内嵌 Node：port=$chosenPort projectDir=${projectDir.absolutePath}")

        val ok = runCatching {
            nativeStartNode(projectDir.absolutePath, entry.absolutePath, env, nodeArgs)
        }.getOrElse { t ->
            Log.e(TAG, "nativeStartNode 抛异常", t)
            loadError = t
            false
        }

        if (!ok) {
            Log.e(TAG, "nativeStartNode 返回失败")
            return null
        }

        started = true
        port = chosenPort

        val base = "http://127.0.0.1:$chosenPort"
        return if (waitUntilReady(base, AppConfig.SERVER_READY_TIMEOUT_MS)) {
            Log.i(TAG, "本地服务已就绪：$base")
            base
        } else {
            Log.e(TAG, "等待本地服务就绪超时：$base（退出码 ${exitCode()}）")
            // 进程还在的话仍然返回地址，交给 WebView 侧自行重试
            if (isRunning) base else null
        }
    }

    fun exitCode(): Int = runCatching { nativeExitCode() }.getOrDefault(-1)

    fun reset() {
        started = false
        port = 0
    }

    // ------------------------------------------------------------ 辅助

    private fun findFreePort(): Int {
        for (offset in 0 until AppConfig.PORT_SCAN_RANGE) {
            val candidate = AppConfig.PREFERRED_PORT + offset
            try {
                ServerSocket(candidate, 1, InetAddress.getByName("127.0.0.1")).use { socket ->
                    socket.reuseAddress = true
                }
                return candidate
            } catch (_: Exception) {
                // 被占用，试下一个
            }
        }
        return -1
    }

    /** 轮询本地服务，直到首页可访问 */
    private fun waitUntilReady(baseUrl: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var attempt = 0
        while (System.currentTimeMillis() < deadline) {
            attempt++
            if (probe(baseUrl)) return true

            // Node 线程已经退出且端口不通，说明起不来了，没必要再等
            if (attempt > 6 && !isRunning) {
                Log.e(TAG, "Node 线程已退出，停止等待")
                return false
            }
            Thread.sleep(if (attempt < 20) 250L else 600L)
        }
        return false
    }

    private fun probe(baseUrl: String): Boolean = try {
        val conn = (URL("$baseUrl/").openConnection() as HttpURLConnection).apply {
            connectTimeout = 2000
            readTimeout = 3000
            requestMethod = "GET"
            instanceFollowRedirects = false
        }
        try {
            conn.responseCode in 200..399
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        false
    }
}
