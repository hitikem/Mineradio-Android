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
 *
 * 这个类里每一步都写进 [BootLog]：失败时不再只有一句「启动失败」，
 * 而是「在哪一步失败、失败时的退出码是多少、端口探测了几次」。
 */
object NodeRunner {

    private const val TAG = "MineradioNodeRunner"

    @Volatile
    private var started = false

    @Volatile
    var port: Int = 0
        private set

    private var loadError: Throwable? = null

    /** 最近一次启动的结论，供界面与诊断报告展示 */
    @Volatile
    var lastDiagnosis: String = ""
        private set

    // ------------------------------------------------------------ JNI

    private external fun nativeStartNode(
        projectDir: String,
        entryScript: String,
        envPairs: Array<String>,
        nodeArgs: Array<String>,
        nativeLogPath: String
    ): Boolean

    private external fun nativeIsRunning(): Boolean

    private external fun nativeExitCode(): Int

    init {
        try {
            // 顺序不能反：libnode.so 必须先加载，mineradio_node.so 依赖它
            System.loadLibrary("node")
            BootLog.i(TAG, "libnode.so 加载成功")
            System.loadLibrary("mineradio_node")
            BootLog.i(TAG, "libmineradio_node.so 加载成功")
        } catch (t: Throwable) {
            loadError = t
            BootLog.e(TAG, "原生库加载失败 —— 后续不可能启动 Node", t)
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
        BootLog.section("内嵌 Node 启动")
        BootLog.i(TAG, "原生库可用=${loadError == null}")

        if (started) {
            BootLog.i(TAG, "已经启动过，直接复用：port=$port")
            return if (port > 0) "http://127.0.0.1:$port" else null
        }

        loadError?.let {
            lastDiagnosis = "原生库加载失败：${it.message}"
            BootLog.e(TAG, "原生库不可用，无法启动", it)
            return null
        }

        // 启动前先把库信息记下来：万一 Node 一声不响地挂掉，这份信息是唯一的线索
        runCatching { BootLog.i(TAG, "原生库信息：\n${nativeReportIfAvailable()}") }
            .onFailure { BootLog.w(TAG, "读取原生库信息失败：${it.message}") }

        val projectDir = AppConfig.nodeAppDir(context)
        val entry = File(projectDir, AppConfig.NODE_ENTRY)
        if (!entry.isFile()) {
            lastDiagnosis = "Node 入口脚本不存在：${entry.absolutePath}"
            BootLog.e(TAG, lastDiagnosis)
            BootLog.e(TAG, "目录内容：${projectDir.list()?.take(30)?.joinToString(", ") ?: "(无法列出)"}")
            return null
        }
        BootLog.i(TAG, "入口脚本：${entry.absolutePath}（${entry.length()} 字节）")
        BootLog.i(TAG, "运行目录：${projectDir.absolutePath}")
        BootLog.i(TAG, "node_modules 存在=${File(projectDir, "node_modules").isDirectory}")

        val chosenPort = findFreePort()
        if (chosenPort <= 0) {
            lastDiagnosis = "找不到可用端口（${AppConfig.PREFERRED_PORT} 起 ${AppConfig.PORT_SCAN_RANGE} 个端口都被占用）"
            BootLog.e(TAG, lastDiagnosis)
            return null
        }
        BootLog.i(TAG, "选定端口：$chosenPort")

        val userData = AppConfig.userDataDir(context).apply { mkdirs() }
        val logDir = AppConfig.logDir(context).apply { mkdirs() }

        val env = arrayOf(
            // 上游 server.js 直接读这两个变量
            "PORT=$chosenPort",
            "HOST=127.0.0.1",
            // 桌面端原本把数据放 app.getPath('userData')，这里换成应用私有目录
            "MINERADIO_ANDROID_USER_DATA=${userData.absolutePath}",
            "MINERADIO_PROJECT_DIR=${projectDir.absolutePath}",
            "MINERADIO_VERSION=${BuildConfig.VERSION_NAME}",
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

        val nativeLog = File(logDir, "native-boot.log").absolutePath

        BootLog.i(TAG, "传给 Node 的环境变量：")
        for (line in env) BootLog.i(TAG, "  $line")
        BootLog.i(TAG, "传给 Node 的参数：${nodeArgs.joinToString(" ")}")
        BootLog.i(TAG, "原生诊断日志：$nativeLog")

        val ok = runCatching {
            nativeStartNode(projectDir.absolutePath, entry.absolutePath, env, nodeArgs, nativeLog)
        }.getOrElse { t ->
            lastDiagnosis = "nativeStartNode 抛异常：${t.message}"
            BootLog.e(TAG, "nativeStartNode 抛异常", t)
            loadError = t
            false
        }

        if (!ok) {
            lastDiagnosis = "nativeStartNode 返回 false（原生层拒绝启动，详见 native-boot.log）"
            BootLog.e(TAG, lastDiagnosis)
            return null
        }

        started = true
        port = chosenPort

        BootLog.i(TAG, "原生层已接受启动请求，开始轮询 http://127.0.0.1:$chosenPort/")

        val base = "http://127.0.0.1:$chosenPort"
        return if (waitUntilReady(base, AppConfig.SERVER_READY_TIMEOUT_MS)) {
            lastDiagnosis = ""
            BootLog.i(TAG, "本地服务已就绪：$base")
            base
        } else {
            val code = exitCode()
            val running = isRunning
            lastDiagnosis = "等待本地服务就绪超时：$base（Node 线程运行中=$running，node::Start 退出码=$code，" +
                "原生诊断见 native-boot.log，脚本日志见 mineradio-node.log）"
            BootLog.e(TAG, lastDiagnosis)
            // 进程还在的话仍然返回地址，交给 WebView 侧自行重试
            if (running) base else null
        }
    }

    fun exitCode(): Int = runCatching { nativeExitCode() }.getOrDefault(-1)

    fun reset() {
        BootLog.i(TAG, "重置 Node 启动状态（重试）")
        started = false
        port = 0
        lastDiagnosis = ""
    }

    private fun nativeReportIfAvailable(): String =
        runCatching { BootLog.nativeReportForDiagnosis() }.getOrDefault("（不可用）")

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
        var lastReported = 0

        while (System.currentTimeMillis() < deadline) {
            attempt++
            if (probe(baseUrl)) {
                BootLog.i(TAG, "第 $attempt 次探测成功")
                return true
            }

            // Node 线程已经退出且端口不通，说明起不来了，没必要再等
            if (attempt > 6 && !isRunning) {
                BootLog.e(TAG, "Node 线程已退出（第 $attempt 次探测时），停止等待，退出码=${exitCode()}")
                return false
            }

            // 每 5 秒记一次进度，避免日志被刷屏，同时能看到「等了多久」
            if (attempt - lastReported >= 5) {
                lastReported = attempt
                BootLog.i(
                    TAG,
                    "第 $attempt 次探测失败，仍在等待（Node 运行中=${isRunning}，" +
                        "已等待 ${(System.currentTimeMillis() - (deadline - timeoutMs)) / 1000}s）"
                )
            }

            Thread.sleep(if (attempt < 20) 250L else 600L)
        }
        BootLog.e(TAG, "探测超时，共尝试 $attempt 次")
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
