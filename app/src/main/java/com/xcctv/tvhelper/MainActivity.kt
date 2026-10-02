package com.xcctv.tvhelper

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), DownloadProgressListener {

    // 设备类型识别：同时兼容 LEANBACK 和旧版 Google TV 设备
    private val isTvDevice: Boolean by lazy {
        packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
        Build.FINGERPRINT.contains("google_tv", ignoreCase = true)
    }

    private lateinit var downloader: XcctvSourceDownloader
    private lateinit var etUrl: EditText
    private lateinit var tvStatus: TextView
    private lateinit var btnStart: Button
    private lateinit var btnClear: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        etUrl = findViewById(R.id.et_url)
        tvStatus = findViewById(R.id.tv_status)
        btnStart = findViewById(R.id.btn_start)
        btnClear = findViewById(R.id.btn_clear)

        // 电视端：放大焦点高亮、调整字体与按钮尺寸
        if (isTvDevice) {
            applyTvFocusStyle()
        }

        // Bug2 fix: ScrollingMovementMethod 让 TextView 可手动滑动下拉
        tvStatus.movementMethod = ScrollingMovementMethod()

        downloader = XcctvSourceDownloader(this)
        val deviceTag = if (isTvDevice) "📺 电视端" else "📱 手机端"
        tvStatus.text = "$deviceTag\n✅就绪\n📁 保存目录: ${downloader.rootDir.absolutePath}\n粘贴源地址 → 开始下载"

        btnStart.setOnClickListener {
            val url = etUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                lifecycleScope.launch {
                    btnStart.isEnabled = false
                    val ret = downloader.run(url)
                    ret.onSuccess {
                        tvStatus.append("\n🎉全部下载完成！")
                    }.onFailure { err ->
                        tvStatus.append("\n❌失败:${err.message}")
                    }
                    btnStart.isEnabled = true
                }
            }
        }

        btnClear.setOnClickListener {
            downloader.clearCache()
            tvStatus.append("\n🗑️缓存已清空")
            scrollToBottom()
        }
    }

    /** Bug2 fix: 滚动到底部的辅助方法 */
    private fun scrollToBottom() {
        val layout = tvStatus.layout ?: return
        val scrollHeight = layout.height - tvStatus.height
        tvStatus.scrollTo(0, maxOf(0, scrollHeight))
    }

    /** 电视端焦点效果放大 + 字号/按钮高度适配 */
    private fun applyTvFocusStyle() {
        val scale = resources.displayMetrics.density
        fun enlarge(view: View) {
            view.setOnFocusChangeListener { v, hasFocus ->
                v.animate()
                    .scaleX(if (hasFocus) 1.12f else 1f)
                    .scaleY(if (hasFocus) 1.12f else 1f)
                    .setDuration(150)
                    .start()
                v.isActivated = hasFocus
            }
        }
        enlarge(btnStart)
        enlarge(btnClear)
        enlarge(etUrl)

        btnStart.textSize = 18f
        btnClear.textSize = 18f
        btnStart.minHeight = (48 * scale).toInt()
        btnClear.minHeight = (48 * scale).toInt()
        etUrl.textSize = 16f
        tvStatus.textSize = 16f
    }

    override fun onProgress(current: Int, total: Int, file: String) {
        runOnUiThread {
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
