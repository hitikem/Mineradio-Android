package com.mineradio.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MineradioApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        installCrashLogger()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_PLAYBACK,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * 把未捕获异常落到文件里。安卓上崩在原生/JS 侧时，
     * 用户往往只能看到「应用已停止」，有日志才有得查。
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val dir = AppConfig.logDir(this)
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, "crash.log")
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                file.appendText(
                    "\n===== $stamp  线程 ${thread.name} =====\n$sw\n"
                )
                Log.e(TAG, "应用崩溃已记录到 ${file.absolutePath}", throwable)
            } catch (t: Throwable) {
                Log.e(TAG, "写崩溃日志失败", t)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        const val TAG = "MineradioApp"
        const val CHANNEL_PLAYBACK = "mineradio.playback"
    }
}
