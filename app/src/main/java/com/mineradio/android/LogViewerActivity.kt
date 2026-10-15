package com.mineradio.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.mineradio.android.databinding.ActivityLogViewerBinding
import java.io.File

/**
 * 启动日志查看页。
 *
 * 用户手上没有 adb，「本地音乐服务启动失败」这句话本身又什么都不说明。
 * 这一页把 Kotlin 启动日志、原生 Node 诊断、Node/server.js 日志合并成一份报告，
 * 直接显示在屏幕上 —— 截图即可，也可以用「分享」把文件发出来。
 */
class LogViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogViewerBinding
    private var report: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        BootLog.i(TAG, "打开日志查看页")

        showProgress()
        binding.logRefresh.setOnClickListener { loadReport() }
        binding.logCopy.setOnClickListener { copyToClipboard() }
        binding.logShare.setOnClickListener { share() }
        binding.logClose.setOnClickListener { finish() }

        loadReport()
    }

    private fun showProgress() {
        binding.logProgress.visibility = View.VISIBLE
        binding.logText.text = getString(R.string.log_loading)
    }

    private fun loadReport() {
        showProgress()
        // 生成报告要读好几 MB 的文件，放到后台线程
        Thread({
            val text = runCatching {
                val file = BootLog.buildReport(this)
                report = file
                buildString {
                    append("诊断报告：").append(file.absolutePath).append('\n')
                    append("大小：").append(file.length() / 1024).append(" KB\n")
                    append("--------------------------------------------------\n")
                    append(file.readText(Charsets.UTF_8))
                }
            }.getOrElse { "生成诊断报告失败：$it" }

            runOnUiThread {
                binding.logProgress.visibility = View.GONE
                binding.logText.text = text
                // 默认滚到底部：故障现场总在最后
                binding.logScroll.post {
                    binding.logScroll.fullScroll(ScrollView.FOCUS_DOWN)
                }
            }
        }, "mineradio-log-report").start()
    }

    private fun copyToClipboard() {
        val text = binding.logText.text?.toString().orEmpty()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Mineradio 启动日志", text))
        Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show()
    }

    private fun share() {
        val file = report ?: BootLog.buildReport(this)
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "Mineradio 启动诊断报告")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    getString(R.string.log_share)
                )
            )
        }.onFailure {
            BootLog.e(TAG, "分享诊断报告失败", it)
            Toast.makeText(this, "分享失败：${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val TAG = "MineradioLogViewer"

        fun start(context: Context) {
            context.startActivity(Intent(context, LogViewerActivity::class.java))
        }
    }
}
