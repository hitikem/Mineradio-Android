package com.mineradio.android

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 启动诊断日志。
 *
 * 为什么要单独有一套日志
 * ----------------------
 * 「本地音乐服务启动失败」这句话本身不携带任何信息：是原生库没加载？是
 * node::Start 直接返回了非 0？还是 Node 起来了但没监听端口？这些都在
 * logcat 里 —— 而用户手上没有 adb。所以这里把**整个启动全过程**同时写进
 * 应用私有目录的文件，配合界面上的「查看启动日志」入口，用户截个图就能定位。
 *
 * 输出分两路：
 *   1) logcat（方便有 adb 时直接过滤 Mineradio 前缀）
 *   2) filesDir/userdata/logs/boot.log（应用内可读、可导出）
 *
 * 注意：**不重定向 stdout/stderr**。原生层已经把 fd 1/2 接到了管道并转发到
 * logcat，这里再做进程级重定向会把 Node 自己的日志也吞掉。
 */
object BootLog {

    private const val TAG = "MineradioBoot"
    private const val MAX_BYTES = 2L * 1024 * 1024

    private val lock = Any()
    private val clock = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var stream: FileOutputStream? = null

    @Volatile
    private var file: File? = null

    @Volatile
    private var broken = false

    /** 诊断报告的完整路径，供界面展示 */
    @Volatile
    var reportFile: File? = null
        private set

    /** 应用启动时调用一次。重复调用是幂等的。 */
    fun init(context: Context) {
        synchronized(lock) {
            if (stream != null) return

            val dir = AppConfig.logDir(context).apply { mkdirs() }
            val target = File(dir, "boot.log")

            // 超过上限就滚动一次，避免长期使用把手机存储写满
            runCatching {
                if (target.isFile && target.length() > MAX_BYTES) {
                    val rotated = File(dir, "boot.log.1")
                    if (rotated.exists()) rotated.delete()
                    target.renameTo(rotated)
                }
            }

            file = target
            stream = runCatching { FileOutputStream(target, true) }.getOrElse {
                broken = true
                Log.w(TAG, "无法写入启动日志：${target.absolutePath}", it)
                null
            }

            i("BootLog", "================ 启动日志开始 ================")
            i("BootLog", "日志文件：${target.absolutePath}")
            writeDeviceInfo()
        }
    }

    fun i(tag: String, message: String) = write("I", tag, message)

    fun w(tag: String, message: String) = write("W", tag, message)

    fun e(tag: String, message: String) = write("E", tag, message)

    fun e(tag: String, message: String, t: Throwable) {
        write("E", tag, "$message\n${Log.getStackTraceString(t)}")
    }

    /** 写一段醒目的分隔，便于在长日志里定位阶段边界 */
    fun section(title: String) {
        write("I", "----", "======== $title ========")
    }

    private fun write(level: String, tag: String, message: String) {
        val line = "${clock.format(Date())} [$level] [$tag] $message"

        when (level) {
            "E" -> Log.e(TAG, "[$tag] $message")
            "W" -> Log.w(TAG, "[$tag] $message")
            else -> Log.i(TAG, "[$tag] $message")
        }

        val out = stream ?: return
        synchronized(lock) {
            runCatching {
                out.write((line + "\n").toByteArray(Charsets.UTF_8))
                // 启动过程是低频写入，直接 flush，保证崩溃/被杀时日志已经落盘
                out.flush()
            }.onFailure {
                broken = true
                stream = null
            }
        }
    }

    // ------------------------------------------------------------ 设备与构建信息

    /**
     * 记录设备信息。特别关注 **内存页大小**：16KB 页机型上，
     * `.so` 的 LOAD 段必须按 16KB 对齐，否则动态链接器直接拒绝加载。
     */
    private fun writeDeviceInfo() {
        i("Device", "机型：${Build.MANUFACTURER} ${Build.MODEL}")
        i("Device", "Android：${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
        i("Device", "ABI：${Build.SUPPORTED_ABIS.joinToString(", ")}")
        i("Device", "内存页大小：${nativePageSize()} 字节")
        i("Device", "应用版本：${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）")

        val runtime = Runtime.getRuntime()
        i(
            "Device",
            "堆上限：${runtime.maxMemory() / 1024 / 1024} MB，" +
                "当前可用：${runtime.freeMemory() / 1024 / 1024} MB"
        )
    }

    /** 读取系统页大小；失败返回 -1 */
    private external fun nativePageSize(): Int

    // ------------------------------------------------------------ 导出

    /**
     * 把 boot.log + native-boot.log + Node 自己的日志合并成一份诊断报告，
     * 供应用内查看与「分享」使用。
     */
    fun buildReport(context: Context): File {
        val dir = AppConfig.logDir(context).apply { mkdirs() }
        val report = File(dir, "mineradio-diagnose.txt")

        val parts = listOf(
            "boot.log（Kotlin 侧启动过程）" to File(dir, "boot.log"),
            "native-boot.log（原生 Node 启动诊断）" to File(dir, "native-boot.log"),
            "mineradio-node.log（Node / server.js 日志）" to File(dir, "mineradio-node.log"),
            "mineradio-node.log.1（上一次的 Node 日志）" to File(dir, "mineradio-node.log.1")
        )

        runCatching {
            report.bufferedWriter(Charsets.UTF_8).use { out ->
                out.write("Mineradio Android 启动诊断报告\n")
                out.write("生成时间：${clock.format(Date())}\n")
                out.write("应用版本：${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）\n")
                out.write("机型：${Build.MANUFACTURER} ${Build.MODEL}\n")
                out.write("Android：${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）\n")
                out.write("ABI：${Build.SUPPORTED_ABIS.joinToString(", ")}\n")
                out.write("内存页大小：${nativePageSize()} 字节\n")
                out.write("原生库报告：\n${runCatching { nativeReport() }.getOrDefault("（读取失败）")}\n")

                for ((title, f) in parts) {
                    out.write("\n\n==================== $title ====================\n")
                    if (!f.isFile) {
                        out.write("（不存在：${f.absolutePath}）\n")
                        continue
                    }
                    out.write("路径：${f.absolutePath}　大小：${f.length()} 字节\n\n")
                    // 日志可能很大，只取尾部：故障现场总在最后
                    val text = readTail(f, 400 * 1024)
                    out.write(text)
                    if (!text.endsWith("\n")) out.write("\n")
                }
            }
            reportFile = report
        }.onFailure {
            e("BootLog", "生成诊断报告失败", it)
        }

        return report
    }

    private fun readTail(f: File, maxBytes: Int): String = runCatching {
        val length = f.length()
        if (length <= maxBytes) {
            f.readText(Charsets.UTF_8)
        } else {
            java.io.RandomAccessFile(f, "r").use { raf ->
                raf.seek(length - maxBytes)
                val buf = ByteArray(maxBytes)
                raf.readFully(buf)
                "（文件过大，仅显示最后 ${maxBytes / 1024} KB）\n" + String(buf, Charsets.UTF_8)
            }
        }
    }.getOrElse { "（读取失败：${it.message}）" }

    /** 原生侧库信息（路径 / 架构 / 加载状态），在 native 层实现 */
    private external fun nativeReport(): String

    /** 对外暴露：供 NodeRunner 等其他组件记录库信息 */
    fun nativeReportForDiagnosis(): String = runCatching { nativeReport() }
        .getOrElse { "（读取原生库信息失败：${it.message}）" }

    init {
        // 静态方法直接可用；若加载失败也绝不抛出，否则会把启动流程带崩
        runCatching { System.loadLibrary("mineradio_node") }
    }
}
