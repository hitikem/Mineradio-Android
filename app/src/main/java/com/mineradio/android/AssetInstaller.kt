package com.mineradio.android

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * 把 assets 里的 Node 运行时压缩包解压到应用私有目录。
 *
 * 关键设计：**覆盖式解压，绝不删除已有目录**。
 * 上游 server.js 把登录态写在自身目录里（`__dirname/.cookie`、`.qq-cookie`、
 * `.kugou-cookie`、`.qishui-cookie`）以及 `__dirname/data/`。如果升级时先删目录再解压，
 * 用户每次更新都会掉登录。覆盖式解压只会改写压缩包里存在的文件，
 * 这些运行期文件自然被保留。
 */
object AssetInstaller {

    private const val TAG = "MineradioAssets"

    fun isUpToDate(context: Context): Boolean {
        val marker = AppConfig.installMarker(context)
        if (!marker.exists()) {
            BootLog.i(TAG, "资源标记不存在，需要解压")
            return false
        }
        val recorded = runCatching { marker.readText().trim() }.getOrNull()
        val ok = recorded == AppConfig.ASSET_REVISION.toString() && sanityCheck(context)
        BootLog.i(
            TAG,
            "资源版本检查：标记=$recorded 期望=${AppConfig.ASSET_REVISION} 校验通过=$ok → 需要解压=${!ok}"
        )
        return ok
    }

    /** 解压后做个最低限度校验，避免半途失败被误判为完成 */
    private fun sanityCheck(context: Context): Boolean {
        val root = AppConfig.nodeAppDir(context)
        val checks = listOf(
            "main.js" to File(root, AppConfig.NODE_ENTRY).isFile,
            "server.js" to File(root, "server.js").isFile,
            "public/index.html" to File(root, "public/index.html").isFile,
            "node_modules/NeteaseCloudMusicApi" to File(root, "node_modules/NeteaseCloudMusicApi").isDirectory
        )
        for ((name, pass) in checks) {
            if (!pass) BootLog.w(TAG, "资源校验失败，缺少：$name")
        }
        return checks.all { it.second }
    }

    /**
     * @param onProgress (已解压字节, 总字节, 当前文件名)
     */
    fun install(context: Context, onProgress: (Long, Long, String) -> Unit) {
        val root = AppConfig.nodeAppDir(context)
        if (!root.exists() && !root.mkdirs()) {
            error("无法创建目录：${root.absolutePath}")
        }

        val totalBytes = runCatching { context.assets.openFd(AppConfig.NODE_APP_ASSET).length }
            .getOrDefault(0L)

        Log.i(TAG, "开始解压 ${AppConfig.NODE_APP_ASSET}（约 ${totalBytes / 1024 / 1024} MB）")
        BootLog.i(TAG, "开始解压 → ${root.absolutePath}（压缩包 ${totalBytes} 字节）")

        var written = 0L
        var entryCount = 0

        context.assets.open(AppConfig.NODE_APP_ASSET, android.content.res.AssetManager.ACCESS_STREAMING).use { raw ->
            ZipInputStream(BufferedInputStream(raw, 64 * 1024)).use { zip ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name

                    // 防御性检查：拒绝任何试图跳出目标目录的路径
                    val target = File(root, name)
                    if (!isInside(root, target)) {
                        Log.w(TAG, "跳过越界条目：$name")
                        zip.closeEntry()
                        continue
                    }

                    if (entry.isDirectory) {
                        target.mkdirs()
                        zip.closeEntry()
                        continue
                    }

                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { fileOut ->
                        BufferedOutputStream(fileOut, 64 * 1024).use { out ->
                            while (true) {
                                val read = zip.read(buffer)
                                if (read <= 0) break
                                out.write(buffer, 0, read)
                                written += read
                            }
                        }
                    }
                    zip.closeEntry()
                    entryCount++

                    if (entryCount % 64 == 0) {
                        onProgress(written, totalBytes, name)
                    }
                }
            }
        }

        // 校验通过才写标记，否则下次启动会重试解压
        if (!sanityCheck(context)) {
            BootLog.e(TAG, "解压后校验失败：缺少入口脚本或依赖（已写入 $entryCount 个文件）")
            error("解压后校验失败：缺少入口脚本或依赖，请重新安装应用")
        }

        AppConfig.installMarker(context).writeText(AppConfig.ASSET_REVISION.toString())
        Log.i(TAG, "解压完成：$entryCount 个文件，${written / 1024 / 1024} MB")
        BootLog.i(TAG, "解压完成：$entryCount 个文件，$written 字节，标记已写入")
        onProgress(written, if (totalBytes > 0) totalBytes else written, "")
    }

    private fun isInside(root: File, target: File): Boolean = try {
        val rootPath = root.canonicalPath + File.separator
        target.canonicalPath.startsWith(rootPath)
    } catch (_: Exception) {
        false
    }

    /** 清空运行期数据（设置里的「重置数据」用） */
    fun wipeUserData(context: Context) {
        val dir = AppConfig.userDataDir(context)
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()
    }
}
