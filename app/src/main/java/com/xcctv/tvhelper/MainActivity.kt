package com.xcctv.tvhelper

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
    private lateinit var httpServer: XcctvHttpServer
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

        downloader = XcctvSourceDownloader(applicationContext, this)
        httpServer = XcctvHttpServer(downloader.rootDir)
        val deviceTag = if (isTvDevice) "📺 电视端" else "📱 手机端"
        var startOk = false
        try {
            httpServer.start()
            startOk = true
        } catch (_: Exception) { /* 端口被占用等 */ }
        val serverMsg = if (startOk) {
            "✅HTTP服务已启动:127.0.0.1:7890\nTVBox: clan://localhost/xcctv/vod.json\nFongMi: file://xcctv/vod.json"
        } else {
            "⚠️HTTP服务启动失败（端口可能被占用）"
        }
        tvStatus.text = "$deviceTag\n$serverMsg"

        btnStart.setOnClickListener {
            val url = etUrl.text.toString().trim()
            if(url.isNotEmpty()){
                lifecycleScope.launch {
                    val ret = downloader.run(url)
                    ret.onSuccess {
                        tvStatus.append("\n🎉全部下载完成！")
                    }.onFailure { err ->
                        tvStatus.append("\n❌失败:${err.message}")
                    }
                }
            }
        }

        btnClear.setOnClickListener {
            downloader.clearCache()
            tvStatus.append("\n🗑️缓存已清空")
        }
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

    override fun onDestroy() {
        super.onDestroy()
        httpServer.stop()
    }

    override fun onProgress(current: Int, total: Int, file: String) {
        runOnUiThread {
            tvStatus.append("\n[$current/$total] $file")
        }
    }

    override fun onLog(msg: String) {
        runOnUiThread {
            tvStatus.append("\n$msg")
        }
    }
}
