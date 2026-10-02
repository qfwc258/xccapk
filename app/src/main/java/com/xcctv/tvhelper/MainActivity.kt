package com.xcctv.tvhelper

import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
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
import kotlinx.coroutines.launch

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

    private val isTvDevice: Boolean by lazy {
        val pm = packageManager
        val hasLeanback = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val hasTvFeature = pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
        val fingerprint = Build.FINGERPRINT.contains("tv", ignoreCase = true)
        val noTouch = !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        hasLeanback || hasTvFeature || fingerprint || noTouch
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

        val deviceLabel = if (isTvDevice) "📺 TV端" else "📱手机端"
        tvTitle.text = "$deviceLabel XCCTV助手"
        tvSubtitle.text = "源地址解析 · 4线程下载 · /sdcard/xcctv"

        val savedUrl = prefs.getString(KEY_LAST_URL, null)
        val defaultUrl = if (isTvDevice) DEFAULT_TV_URL else DEFAULT_PHONE_URL
        etUrl.setText(savedUrl ?: defaultUrl)

        refreshPermStatus()

        if (isTvDevice) {
            setupTvFocusAnim()
        }

        tvStatus.movementMethod = ScrollingMovementMethod()
        progress.visibility = View.GONE

        downloader = XcctvSourceDownloader(this, concurrency = 2)

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

        btnPerm.setOnClickListener { requestStoragePerm() }
    }

    private fun setupTvFocusAnim() {
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
        btnClear.onFocusChangeListener = focusListener
        btnFolder.onFocusChangeListener = focusListener
        btnPerm.onFocusChangeListener = focusListener
        tvStatus.onFocusChangeListener = focusListener
    }

    private fun refreshPermStatus() {
        val ok = hasStoragePermission()
        if (ok) {
            tvPermStatus.text = "✅ 已授权 /sdcard 写入"
            tvPermStatus.setTextColor(0xFF22C55E.toInt())
            btnPerm.text = "✓已授权"
            btnPerm.isEnabled = false
            btnStart.isEnabled = true
        } else {
            tvPermStatus.text = "⚠️未授予全部文件权限 → 点按钮授权"
            tvPermStatus.setTextColor(0xFFFFB74D.toInt())
            btnPerm.text = "🔐授权"
            btnPerm.isEnabled = true
            btnStart.isEnabled = false
        }
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePerm() {
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
            tvStatus.append("\n🚫必须先授予存储权限！")
            scrollBottom()
            return
        }
        val url = etUrl.text.toString().trim()
        if (url.isEmpty()) {
            tvStatus.append("\n⚠️源地址不能为空！")
            scrollBottom()
            return
        }
        prefs.edit().putString(KEY_LAST_URL, url).apply()

        btnStart.isEnabled = false
        btnClear.isEnabled = false
        btnPerm.isEnabled = false
        progress.visibility = View.VISIBLE
        tvStatus.text = ""

        doneCount = 0
        totalCount = 0
        tvBanner.visibility = View.VISIBLE
        tvBanner.setBackgroundColor(0xFF0284C7.toInt())
        tvBanner.text = "⏳开始下载源文件"

        scrollBottom()

        lifecycleScope.launch {
            // ============ 【正确调用！！】run() 函数，并发写死2 ============
            val result = downloader.run(url)
            btnStart.isEnabled = true
            btnClear.isEnabled = true
            btnPerm.isEnabled = !hasStoragePermission()
            progress.visibility = View.GONE
            if (result.isSuccess) {
                tvBanner.setBackgroundColor(0xFF16A34A.toInt())
                tvBanner.text = "✅下载完成"
                tvStatus.append("\n🎉全部文件下载完成，保存至 /sdcard/xcctv")
            } else {
                tvBanner.setBackgroundColor(0xFFDC2626.toInt())
                tvBanner.text = "❌下载失败"
                tvStatus.append("\n💥失败：${result.exceptionOrNull()?.message}")
            }
            scrollBottom()
        }
    }

    private fun scrollBottom() {
        tvStatus.post {
            tvStatus.scrollTo(0, tvStatus.height)
        }
    }

    // ========== 实现 DownloadProgressListener 回调（和你下载器接口完全对应） ==========
    override fun onProgress(current: Int, total: Int, file: String) {
        runOnUiThread {
            doneCount = current
            totalCount = total
            tvBanner.text = "⏳下载 $current/$total : $file"
            tvStatus.append("\n[$current/$total] $file")
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
