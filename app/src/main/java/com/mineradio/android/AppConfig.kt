package com.mineradio.android

import android.content.Context
import java.io.File

/**
 * 全局配置：路径、端口、资源版本。
 *
 * 目录约定（都落在应用私有空间，卸载即清理，不需要任何存储权限）：
 *
 *   filesDir/node-app/     ← 从 assets 解压出来的 Node 运行时（server.js + public/ + node_modules）
 *   filesDir/userdata/     ← 登录 Cookie、播放缓存、日志等运行期数据
 *   filesDir/userdata/logs ← 日志，可在应用内导出
 */
object AppConfig {

    /**
     * 资源包版本号。每次改动 node-app/ 内容后 +1，
     * 应用启动时会据此判断是否需要重新解压。
     */
    const val ASSET_REVISION = 1

    /** assets 里的 Node 运行时压缩包名 */
    const val NODE_APP_ASSET = "mineradio-node-app.zip"

    /** 解压完成的标记文件（内容为 ASSET_REVISION） */
    const val INSTALL_MARKER = ".mineradio-asset-revision"

    /** Node 入口脚本，相对 node-app 根目录 */
    const val NODE_ENTRY = "main.js"

    /** 首选端口；被占用时自动向后顺延 */
    const val PREFERRED_PORT = 17321

    /** 端口顺延的最大尝试次数 */
    const val PORT_SCAN_RANGE = 24

    /** 等待本地服务就绪的上限（毫秒）。首次启动要解压资源，给得宽松一些。 */
    const val SERVER_READY_TIMEOUT_MS = 60_000L

    /** V8 堆上限。手机上给太大容易 OOM，给太小歌词舞台会卡。 */
    const val NODE_MAX_OLD_SPACE_MB = 768

    fun nodeAppDir(context: Context): File = File(context.filesDir, "node-app")

    fun userDataDir(context: Context): File = File(context.filesDir, "userdata")

    fun logDir(context: Context): File = File(userDataDir(context), "logs")

    fun nodeLogFile(context: Context): File = File(logDir(context), "mineradio-node.log")

    fun installMarker(context: Context): File = File(nodeAppDir(context), INSTALL_MARKER)
}
