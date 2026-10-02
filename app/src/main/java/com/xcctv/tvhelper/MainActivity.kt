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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), DownloadProgressListener {

    companion object {
        private const val REQ_PERM = 1001
        private const val FOCUS_SCALE = 1.08f
    }

    private val isTvDevice: Boolean by lazy {
        val pm = packageManager
        pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
            Build.FINGERPRINT.contains("tv", ignoreCase = true) ||
            !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
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
    private lateinit var cbBoot: CheckBox
    private lateinit var progress: ProgressBar
    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(AppConstants.PREFS_NAME, MODE_PRIVATE)
        bindViews()

        tvTitle.setText(if (isTvDevice) R.string.title_tv else R.string.title_phone)
        tvSubtitle.setText(R.string.subtitle)

        val savedUrl = prefs.getString(AppConstants.KEY_LAST_URL, null)
        etUrl.setText(savedUrl ?: AppConstants.DEFAULT_SOURCE_URL)

        refreshPermStatus()
        if (isTvDevice) setupTvFocusAnim()

        tvStatus.movementMethod = ScrollingMovementMethod()
        progress.visibility = View.GONE
        tvBanner.visibility = View.GONE

        downloader = XcctvSourceDownloader(this, AppConstants.DOWNLOAD_CONCURRENCY)

        btnStart.setOnClickListener { startDownload() }
        btnClear.setOnClickListener {
            etUrl.setText("")
            etUrl.requestFocus()
            tvBanner.visibility = View.GONE
        }
        btnFolder.setOnClickListener {
            etUrl.setText(AppConstants.DEFAULT_SOURCE_URL)
            etUrl.setSelection(etUrl.text.length)
            etUrl.requestFocus()
        }
        btnPerm.setOnClickListener { requestStoragePerm() }
        cbBoot.isChecked = prefs.getBoolean(AppConstants.KEY_BOOT_LAUNCH, false)
        cbBoot.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(AppConstants.KEY_BOOT_LAUNCH, checked).apply()
        }
    }

    private fun bindViews() {
        etUrl = findViewById(R.id.et_url)
        tvStatus = findViewById(R.id.tv_status)
        tvPermStatus = findViewById(R.id.tv_perm_status)
        btnStart = findViewById(R.id.btn_start)
        btnClear = findViewById(R.id.btn_clear)
        btnFolder = findViewById(R.id.btn_folder)
        btnPerm = findViewById(R.id.btn_perm)
        cbBoot = findViewById(R.id.cb_boot)
        progress = findViewById(R.id.progress)
        tvTitle = findViewById(R.id.tv_title)
        tvSubtitle = findViewById(R.id.tv_subtitle)
        tvBanner = findViewById(R.id.tv_banner)
    }

    private fun setupTvFocusAnim() {
        val focusListener = View.OnFocusChangeListener { v, hasFocus ->
            val scale = if (hasFocus) FOCUS_SCALE else 1.0f
            v.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
        }
        listOf(etUrl, btnStart, btnClear, btnFolder, btnPerm, cbBoot, tvStatus).forEach {
            it.onFocusChangeListener = focusListener
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermStatus()
    }

    private fun refreshPermStatus() {
        val ok = hasStoragePermission()
        if (ok) {
            tvPermStatus.setText(R.string.perm_granted)
            tvPermStatus.setTextColor(ContextCompat.getColor(this, R.color.status_ok))
            btnPerm.setText(R.string.btn_permission_granted)
            btnPerm.isEnabled = false
            btnStart.isEnabled = true
        } else {
            tvPermStatus.setText(R.string.perm_denied)
            tvPermStatus.setTextColor(ContextCompat.getColor(this, R.color.status_warn))
            btnPerm.setText(R.string.btn_grant_permission)
            btnPerm.isEnabled = true
            btnStart.isEnabled = false
        }
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePerm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:$packageName")
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_PERM)
        } else {
            requestPermissions(
                arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQ_PERM
            )
        }
    }

    @Deprecated("Deprecated in Java")
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
            appendLog(getString(R.string.log_need_permission))
            return
        }
        val url = etUrl.text.toString().trim()
        if (url.isEmpty()) {
            appendLog(getString(R.string.log_empty_url))
            return
        }
        prefs.edit().putString(AppConstants.KEY_LAST_URL, url).apply()

        btnStart.isEnabled = false
        btnClear.isEnabled = false
        btnPerm.isEnabled = false
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        tvStatus.text = ""

        tvBanner.visibility = View.VISIBLE
        tvBanner.setBackgroundResource(R.drawable.bg_banner_loading)
        tvBanner.setText(R.string.banner_start)

        lifecycleScope.launch {
            val result = downloader.run(url)
            btnStart.isEnabled = true
            btnClear.isEnabled = true
            btnPerm.isEnabled = !hasStoragePermission()
            progress.visibility = View.GONE
            progress.isIndeterminate = false
            if (result.isSuccess) {
                tvBanner.setBackgroundResource(R.drawable.bg_banner_done)
                tvBanner.setText(R.string.banner_done)
                appendLog(getString(R.string.log_all_done))
            } else {
                tvBanner.setBackgroundResource(R.drawable.bg_banner_fail)
                tvBanner.setText(R.string.banner_fail)
                appendLog(getString(R.string.log_fail, result.exceptionOrNull()?.message ?: ""))
            }
        }
    }

    private fun appendLog(msg: String) {
        if (tvStatus.text.isNullOrEmpty()) {
            tvStatus.text = msg
        } else {
            tvStatus.append("\n$msg")
        }
        scrollBottom()
    }

    private fun scrollBottom() {
        tvStatus.post {
            val layout = tvStatus.layout ?: return@post
            val scrollAmount = layout.getLineTop(tvStatus.lineCount) - tvStatus.height
            if (scrollAmount > 0) tvStatus.scrollTo(0, scrollAmount) else tvStatus.scrollTo(0, 0)
        }
    }

    override fun onProgress(current: Int, total: Int, file: String) {
        runOnUiThread {
            tvBanner.text = getString(R.string.banner_progress, current, total, file)
            appendLog(getString(R.string.log_progress_item, current, total, file))
        }
    }

    override fun onLog(msg: String) {
        runOnUiThread { appendLog(msg) }
    }
}
