package com.xcctv.tvhelper

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope

class MainActivity : AppCompatActivity(), DownloadProgressListener {

    companion object {
        private const val PREFS_NAME = "xcctv_prefs"
        private const val KEY_LAST_URL = "last_source_url"
        private const val REQ_PERM = 1001

        private const val DEFAULT_TV_URL =
            "https://gh-proxy.org/https://raw.githubusercontent.com/qfwc258/xccapk/main/tv/vod.json"
        private const val DEFAULT_PHONE_URL =
            "https://gh-proxy.org/https://raw.githubusercontent.com/qfwc258/xccapk/main/tv/vod.json"
    }

    /**
     * 增强TV设备识别，适配魔百盒、UNT413等国产无Leanback盒子
     */
    private val isTvDevice: Boolean by lazy {
        val pm = packageManager
        val hasLeanback = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val hasTv = pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
        val fingerprint = Build.FINGERPRINT.contains("tv", ignoreCase = true)
        val noTouch = !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        hasLeanback || hasTv || fingerprint || noTouch
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var downloader: XcctvSourceDownloader
    private lateinit var etUrl: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvPermStatus: TextView
    private lateinit var tvBanner: TextView
    private lateinit var btnStart: Button
    private lateinit var btnClear: Button
    private lateinit var btnFolder: Button
    private lateinit var btnPerm: Button
    private lateinit var progress: ProgressBar
    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView

    private var totalCount = 0
    private var doneCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        etUrl = findViewById(R.id.et_url)
        tvStatus = findViewById(R.id.tv_status)
        tvPermStatus = findViewById(R.id.tv_perm_status)
        btnStart = findViewById(R.id.btn_start)
        btnClear = findViewById(R.id.btn_clear)
        btnFolder = findViewById(R.id.btn_folder)
        btnPerm = findViewById(R.id.btn_perm)
        progress = findViewById(R.id.progress)
        tvTitle = findViewById(R.id.tv_title)
        tvSubtitle = findViewById(R.id.tv_subtitle)
        tvBanner = findViewById(R.id.tv_banner)
        tvBanner.visibility = View.GONE

        val deviceLabel = if (isTvDevice) "📺 TV端" else "📱 手机端"
        tvTitle.text = "$deviceLabel XCCTV助手"
        tvSubtitle.text = "源地址解析 · 4线程下载 · /sdcard/xcctv"

        val savedUrl = prefs.getString(KEY_LAST_URL, null)
        val defaultUrl = if (isTvDevice) DEFAULT_TV_URL else DEFAULT_PHONE_URL
        etUrl.setText(savedUrl ?: defaultUrl)

        refreshPermStatus()

        // TV端额外开启焦点标记（焦点顺序在xml定义，kt不再写nextFocus代码）
        if (isTvDevice) {
            setupTvFocusStyle()
        }

        tvStatus.movementMethod = ScrollingMovementMethod()
        progress.visibility = View.GONE

        downloader = XcctvSourceDownloader(this)

        btnStart.setOnClickListener { startDownload() }

        btnClear.setOnClickListener {
            etUrl.setText("")
            etUrl.requestFocus()
            tvBanner.visibility = View.GONE
        }

        btnFolder.setOnClickListener {
            val defaultUrlVal = if (isTvDevice) DEFAULT_TV_URL else DEFAULT_PHONE_URL
            etUrl.setText(defaultUrlVal)
            etUrl.setSelection(defaultUrlVal.length)
            etUrl.requestFocus()
        }

        btnPerm.setOnClickListener { requestStoragePermission() }
    }

    /**
     * TV 仅设置焦点动画样式，焦点跳转全部交给 activity_main.xml
     */
    private fun setupTvFocusStyle() {
        val focusListener = View.OnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.scaleX = 1.1f
                v.scaleY = 1.1f
            } else {
                v.scaleX = 1.0f
                v.scaleY = 1.0f
            }
        }
        etUrl.onFocusChangeListener = focusListener
        btnStart.onFocusChangeListener = focusListener
        tvStatus.onFocusChangeListener = focusListener
        btnPerm.onFocusChangeListener = focusListener
        btnClear.onFocusChangeListener = focusListener
        btnFolder.onFocusChangeListener = focusListener
    }

    private fun refreshPermStatus() {
        val ok = hasStoragePermission()
        if (ok) {
            tvPermStatus.text = "✅ 已授权 /sdcard 写入"
            tvPermStatus.setTextColor(0xFF22C55E.toInt())
            btnPerm.text = "✓ 已授权"
            btnPerm.isEnabled = false
            btnStart.isEnabled = true
        } else {
            tvPermStatus.text = "⚠️ 未授权存储权限 → 点按钮授权"
            tvPermStatus.setTextColor(0xFFF59E0B.toInt())
            btnPerm.text = "🔐 授权"
            btnPerm.isEnabled = true
            btnStart.isEnabled = true
        }
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:$packageName")
            startActivityForResult(intent, REQ_PERM)
        } else {
            requestPermissions(
                arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQ_PERM
            )
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PERM) refreshPermStatus()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM) refreshPermStatus()
    }

    private fun startDownload() {
        if (!hasStoragePermission()) {
            tvStatus.append("\n🚫 需要先授予存储权限！")
            scrollBottom()
            requestStoragePermission()
            return
        }
        val url = etUrl.text.toString().trim()
        if (url.isEmpty()) {
            tvStatus.append("\n⚠️ 源地址不能为空")
            return
        }
        prefs.edit().putString(KEY_LAST_URL, url).apply()

        btnStart.isEnabled = false
        btnClear.isEnabled = false
        btnPerm.isEnabled = false
        progress.visibility = View.VISIBLE
        tvStatus.text = ""

        totalCount = 0
        doneCount = 0
        tvBanner.visibility = View.VISIBLE
        tvBanner.setBackgroundColor(0xFF0284C7.toInt())
        tvBanner.text = "⏳ 开始下载源文件"

        scrollBottom()
        // 下载启动后焦点切到日志框
        tvStatus.requestFocus()

        lifecycleScope.launch {
            // ==========【只改这里！】downloadSource → download，适配你原来的下载方法 ==========
            val result = downloader.download(url)
            btnStart.isEnabled = true
            btnClear.isEnabled = true
            btnPerm.isEnabled = !hasStoragePermission()
            progress.visibility = View.GONE
            if (result.isSuccess) {
                tvBanner.setBackgroundColor(0xFF16A34A.toInt())
                tvBanner.text = "✅ 下载完成，共 $doneCount 个文件"
                tvStatus.append("\n🎉 全部资源下载到 /sdcard/xcctv")
            } else {
                tvBanner.setBackgroundColor(0xFFDC2626.toInt())
                tvBanner.text = "❌ 下载失败"
                tvStatus.append("\n❌ ${result.exceptionOrNull()?.message}")
            }
            scrollBottom()
        }
    }

    private fun scrollBottom() {
        tvStatus.post {
            tvStatus.scrollTo(0, tvStatus.height)
        }
    }

    override fun onProgress(current: Int, total: Int, fileName: String) {
        runOnUiThread {
            doneCount = current
            totalCount = total
            tvBanner.text = "⏳ 下载中 $current / $total"
            tvStatus.append("\n[$current/$total] $fileName")
            scrollBottom()
        }
    }

    override fun onLog(msg: String) {
        runOnUiThread {
            tvStatus.append("\n$msg")
            scrollBottom()
        }
    }
}
