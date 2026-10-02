package com.xcctv.tvhelper

import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), DownloadProgressListener {

    companion object {
        private const val PREFS = "xcctv_prefs"
        private const val KEY_LAST_URL = "last_source_url"
        // TV 端默认远程源（gh-proxy GitHub 镜像）
        private const val DEFAULT_TV_URL =
            "https://gh-proxy.org/https://raw.githubusercontent.com/qfwc258/xccapk/main/tv/vod.json"
        private const val DEFAULT_PHONE_URL =
            "https://gh-proxy.org/https://raw.githubusercontent.com/qfwc258/xccapk/main/tv/vod.json"
    }

    private val isTvDevice: Boolean by lazy {
        packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
        Build.FINGERPRINT.contains("google_tv", ignoreCase = true) ||
        Build.FINGERPRINT.contains("androidtv", ignoreCase = true)
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var downloader: XcctvSourceDownloader
    private lateinit var etUrl: EditText
    private lateinit var tvStatus: TextView
    private lateinit var btnStart: Button
    private lateinit var btnClear: Button
    private lateinit var btnFolder: Button
    private lateinit var progress: ProgressBar
    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        // 绑定 Views
        etUrl = findViewById(R.id.et_url)
        tvStatus = findViewById(R.id.tv_status)
        btnStart = findViewById(R.id.btn_start)
        btnClear = findViewById(R.id.btn_clear)
        btnFolder = findViewById(R.id.btn_folder)
        progress = findViewById(R.id.progress)
        tvTitle = findViewById(R.id.tv_title)
        tvSubtitle = findViewById(R.id.tv_subtitle)

        // ✅ TV/手机差异初始化
        val deviceTag = if (isTvDevice) "📺 TV端" else "📱 手机端"
        tvTitle.text = "$deviceTag  XCCTV助手"
        tvSubtitle.text = "源地址解析 · 并行下载 · 本地缓存"

        // ✅ TV 端默认源 / 手机端记住上次
        val saved = prefs.getString(KEY_LAST_URL, null)
        etUrl.setText(saved ?: if (isTvDevice) DEFAULT_TV_URL else DEFAULT_PHONE_URL)

        // ✅ TV 端遥控器焦点链：URL → 开始下载 → 清空 → 打开目录
        if (isTvDevice) {
            etUrl.isFocusable = true
            btnStart.isFocusable = true
            btnClear.isFocusable = true
            btnFolder.isFocusable = true
            etUrl.nextFocusDownId = R.id.btn_start
            btnStart.nextFocusUpId = R.id.et_url
            btnStart.nextFocusDownId = R.id.btn_clear
            btnClear.nextFocusUpId = R.id.btn_start
            btnClear.nextFocusRightId = R.id.btn_folder
            btnFolder.nextFocusLeftId = R.id.btn_clear
            btnFolder.nextFocusUpId = R.id.btn_start
            // 焦点放大动效
            val scale = resources.displayMetrics.density
            fun enlarge(v: View, minHeightDp: Int = 48, textSp: Float = 18f) {
                (v as? Button)?.textSize = textSp
                v.minimumHeight = (minHeightDp * scale).toInt()
                v.setOnFocusChangeListener { view, has ->
                    view.animate().scaleX(if (has) 1.15f else 1f).scaleY(if (has) 1.15f else 1f).duration = 150
                    view.isActivated = has
                }
            }
            enlarge(etUrl, 56, 16f); enlarge(btnStart); enlarge(btnClear); enlarge(btnFolder)
            // 遥控器打开即聚焦 URL
            etUrl.requestFocus()
        }

        // ✅ ScrollingMovementMethod 让 TextView 可手动下拉
        tvStatus.movementMethod = ScrollingMovementMethod()
        progress.visibility = View.INVISIBLE

        downloader = XcctvSourceDownloader(this)

        btnStart.setOnClickListener { startDownload() }
        btnClear.setOnClickListener {
            downloader.clearCache()
            tvStatus.append("\n🗑️缓存已清空")
            scrollToBottom()
        }
        btnFolder.setOnClickListener {
            // 尽量用系统文件管理器打开目录；全部失败就把路径打印到日志里
            val dir = downloader.rootDir
            val opened = tryOpenFolderWithSystemApp(dir)
            if (!opened) {
                tvStatus.append("\n📁 路径: ${dir.absolutePath}")
                scrollToBottom()
            }
        }
    }

    /**
     * 依次尝试多种方式唤起系统文件管理器打开目录。
     * Android 11+ 可能被限制，全部失败返回 false。
     */
    private fun tryOpenFolderWithSystemApp(dir: java.io.File): Boolean {
        if (!dir.exists()) return false
        val candidates = listOf(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(android.net.Uri.fromFile(dir), "*/*")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent(Intent.ACTION_VIEW).apply {
                data = android.net.Uri.fromFile(dir)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        for (intent in candidates) {
            try {
                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(Intent.createChooser(intent, "打开文件夹"))
                    return true
                }
            } catch (_: Exception) { /* 下一种 */ }
        }
        return false
    }

    private fun startDownload() {
        val url = etUrl.text.toString().trim()
        if (url.isEmpty()) {
            tvStatus.append("\n⚠️请先输入源地址")
            return
        }
        // ✅ 记住上次源
        prefs.edit().putString(KEY_LAST_URL, url).apply()

        btnStart.isEnabled = false
        btnClear.isEnabled = false
        progress.visibility = View.VISIBLE
        tvStatus.text = ""
        scrollToBottom()

        lifecycleScope.launch {
            val ret = downloader.run(url)
            btnStart.isEnabled = true
            btnClear.isEnabled = true
            progress.visibility = View.INVISIBLE
            if (ret.isSuccess) {
                tvStatus.append("\n✅下载完成！去 /sdcard/xcctv 查看")
            } else {
                tvStatus.append("\n❌失败: ${ret.exceptionOrNull()?.message}")
            }
            scrollToBottom()
        }
    }

    private fun scrollToBottom() {
        tvStatus.post {
            val layout = tvStatus.layout ?: return@post
            tvStatus.scrollTo(0, maxOf(0, layout.height - tvStatus.height))
        }
    }

    override fun onProgress(current: Int, total: Int, file: String) {
        runOnUiThread {
            progress.max = total.coerceAtLeast(1)
            progress.progress = current
            tvStatus.append("\n[$current/$total] $file")
            scrollToBottom()
        }
    }

    override fun onLog(msg: String) {
        runOnUiThread {
            tvStatus.append("\n$msg")
            scrollToBottom()
        }
    }
}
