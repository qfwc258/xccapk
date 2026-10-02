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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.ArrayDeque

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
    private lateinit var spSource: Spinner
    private lateinit var tvStatus: TextView
    private lateinit var tvPermStatus: TextView
    private lateinit var tvBanner: TextView
    private lateinit var tvSavePath: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnPerm: Button
    private lateinit var cbBoot: CheckBox
    private lateinit var progress: ProgressBar
    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView

    private val logLines = ArrayDeque<String>()
    private var downloading = false
    private var sourceReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(AppConstants.PREFS_NAME, MODE_PRIVATE)
        bindViews()

        tvTitle.setText(if (isTvDevice) R.string.title_tv else R.string.title_phone)
        tvSubtitle.setText(R.string.subtitle)

        setupSourceSpinner()
        refreshPermStatus()
        if (isTvDevice) setupTvFocusAnim()

        tvStatus.movementMethod = ScrollingMovementMethod()
        progress.visibility = View.GONE
        tvBanner.visibility = View.GONE

        downloader = XcctvSourceDownloader(this, this, AppConstants.DOWNLOAD_CONCURRENCY)

        btnStart.setOnClickListener { startDownload() }
        btnStop.setOnClickListener { stopDownload() }
        btnPerm.setOnClickListener { requestStoragePerm() }
        cbBoot.isChecked = prefs.getBoolean(AppConstants.KEY_BOOT_LAUNCH, false)
        cbBoot.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(AppConstants.KEY_BOOT_LAUNCH, checked).apply()
        }
    }

    private fun bindViews() {
        etUrl = findViewById(R.id.et_url)
        spSource = findViewById(R.id.sp_source)
        tvStatus = findViewById(R.id.tv_status)
        tvPermStatus = findViewById(R.id.tv_perm_status)
        tvSavePath = findViewById(R.id.tv_save_path)
        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)
        btnPerm = findViewById(R.id.btn_perm)
        cbBoot = findViewById(R.id.cb_boot)
        progress = findViewById(R.id.progress)
        tvTitle = findViewById(R.id.tv_title)
        tvSubtitle = findViewById(R.id.tv_subtitle)
        tvBanner = findViewById(R.id.tv_banner)
    }

    private fun setupSourceSpinner() {
        val names = AppConstants.SOURCE_PRESETS.map { it.name }
        spSource.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        restoreSourceSelection()
        sourceReady = true
        spSource.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!sourceReady) return
                val preset = AppConstants.SOURCE_PRESETS.getOrNull(position) ?: return
                if (preset.url.isEmpty()) {
                    etUrl.visibility = View.VISIBLE
                    if (etUrl.text.isNullOrBlank()) etUrl.requestFocus()
                } else {
                    etUrl.visibility = View.GONE
                    etUrl.setText(preset.url)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun restoreSourceSelection() {
        val savedUrl = prefs.getString(AppConstants.KEY_LAST_URL, null)
            ?: AppConstants.DEFAULT_SOURCE_URL
        val match = AppConstants.SOURCE_PRESETS.indexOfFirst { it.url.isNotEmpty() && it.url == savedUrl }
        if (match >= 0) {
            spSource.setSelection(match)
            etUrl.visibility = View.GONE
            etUrl.setText(savedUrl)
        } else {
            val custom = AppConstants.SOURCE_PRESETS.indexOfFirst { it.url.isEmpty() }.coerceAtLeast(0)
            spSource.setSelection(custom)
            etUrl.visibility = View.VISIBLE
            etUrl.setText(savedUrl)
        }
    }

    private fun currentSourceUrl(): String {
        val preset = AppConstants.SOURCE_PRESETS.getOrNull(spSource.selectedItemPosition)
        return if (preset == null || preset.url.isEmpty()) {
            etUrl.text.toString().trim()
        } else {
            preset.url
        }
    }

    private fun setupTvFocusAnim() {
        val focusListener = View.OnFocusChangeListener { v, hasFocus ->
            val scale = if (hasFocus) FOCUS_SCALE else 1.0f
            v.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
        }
        listOf(spSource, etUrl, btnStart, btnStop, btnPerm, cbBoot, tvStatus).forEach {
            it.onFocusChangeListener = focusListener
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermStatus()
    }

    private fun refreshPermStatus() {
        val root = resolveRootDir(this)
        tvSavePath.text = getString(R.string.save_path, root.absolutePath)
        val writable = root.exists() && root.canWrite()
        when {
            hasStoragePermission() && isPrimaryRoot(root) -> {
                tvPermStatus.setText(R.string.perm_granted)
                tvPermStatus.setTextColor(ContextCompat.getColor(this, R.color.status_ok))
                btnPerm.setText(R.string.btn_permission_granted)
                btnPerm.isEnabled = false
            }
            writable -> {
                tvPermStatus.setText(R.string.perm_fallback)
                tvPermStatus.setTextColor(ContextCompat.getColor(this, R.color.status_warn))
                btnPerm.setText(R.string.btn_grant_permission)
                btnPerm.isEnabled = !downloading
            }
            else -> {
                tvPermStatus.setText(R.string.perm_denied)
                tvPermStatus.setTextColor(ContextCompat.getColor(this, R.color.status_warn))
                btnPerm.setText(R.string.btn_grant_permission)
                btnPerm.isEnabled = !downloading
            }
        }
        if (!downloading) {
            btnStart.isEnabled = writable
            btnStop.isEnabled = false
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
        val url = currentSourceUrl()
        if (url.isEmpty()) {
            appendLog(getString(R.string.log_empty_url))
            return
        }
        val root = resolveRootDir(this)
        if (!root.exists() || !root.canWrite()) {
            appendLog(getString(R.string.perm_denied))
            return
        }
        prefs.edit().putString(AppConstants.KEY_LAST_URL, url).apply()

        downloading = true
        btnStart.isEnabled = false
        btnStop.isEnabled = true
        btnPerm.isEnabled = false
        spSource.isEnabled = false
        etUrl.isEnabled = false
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        progress.progress = 0
        progress.max = 100
        logLines.clear()
        tvStatus.text = ""

        tvBanner.visibility = View.VISIBLE
        tvBanner.setBackgroundResource(R.drawable.bg_banner_loading)
        tvBanner.setText(R.string.banner_start)
        tvSavePath.text = getString(R.string.save_path, root.absolutePath)

        lifecycleScope.launch {
            val result = downloader.run(url)
            downloading = false
            btnStart.isEnabled = true
            btnStop.isEnabled = false
            spSource.isEnabled = true
            etUrl.isEnabled = true
            progress.isIndeterminate = false
            refreshPermStatus()
            result.fold(
                onSuccess = { summary ->
                    progress.max = summary.discovered.coerceAtLeast(1)
                    progress.progress = (summary.downloaded + summary.skipped).coerceAtMost(progress.max)
                    tvSavePath.text = getString(R.string.save_path, summary.rootDir)
                    when {
                        summary.cancelled -> {
                            tvBanner.setBackgroundResource(R.drawable.bg_banner_fail)
                            tvBanner.setText(R.string.banner_cancelled)
                            appendLog(getString(R.string.log_cancelled))
                        }
                        summary.failed.isNotEmpty() -> {
                            tvBanner.setBackgroundResource(R.drawable.bg_banner_fail)
                            tvBanner.setText(R.string.banner_partial)
                            appendLog(getString(R.string.log_all_done, summary.rootDir))
                        }
                        else -> {
                            tvBanner.setBackgroundResource(R.drawable.bg_banner_done)
                            tvBanner.setText(R.string.banner_done)
                            appendLog(getString(R.string.log_all_done, summary.rootDir))
                        }
                    }
                },
                onFailure = { err ->
                    progress.visibility = View.GONE
                    tvBanner.setBackgroundResource(R.drawable.bg_banner_fail)
                    tvBanner.setText(R.string.banner_fail)
                    appendLog(getString(R.string.log_fail, err.message ?: ""))
                }
            )
        }
    }

    private fun stopDownload() {
        downloader.cancel()
        btnStop.isEnabled = false
        tvBanner.setText(R.string.banner_cancelled)
    }

    private fun appendLog(msg: String) {
        logLines.addLast(msg)
        while (logLines.size > AppConstants.MAX_LOG_LINES) {
            logLines.removeFirst()
        }
        tvStatus.text = logLines.joinToString("\n")
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
            val t = total.coerceAtLeast(1)
            progress.max = t
            progress.progress = current.coerceIn(0, t)
            progress.isIndeterminate = false
            tvBanner.text = getString(R.string.banner_progress, current, total, file)
        }
    }

    override fun onLog(msg: String) {
        runOnUiThread { appendLog(msg) }
    }
}
