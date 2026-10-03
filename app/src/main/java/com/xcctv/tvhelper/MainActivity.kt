package com.xcctv.tvhelper

import android.app.UiModeManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
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
    }

    private val isTvDevice: Boolean by lazy {
        val uiMode = getSystemService(UI_MODE_SERVICE) as? UiModeManager
        val tvMode = uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        val pm = packageManager
        tvMode ||
            pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
            Build.FINGERPRINT.contains("tv", ignoreCase = true) ||
            !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var downloader: XcctvSourceDownloader
    private lateinit var etUrl: EditText
    private lateinit var customRow: View
    private lateinit var btnClear: Button
    private lateinit var btnPaste: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvPermStatus: TextView
    private lateinit var tvBanner: TextView
    private lateinit var tvSavePath: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnPerm: Button
    private lateinit var cbBoot: CheckBox
    private var cbAutoUpdate: CheckBox? = null
    private var cbHttpServer: CheckBox? = null
    private var tvHttpAddr: TextView? = null
    private lateinit var progress: ProgressBar
    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var tvDevice: TextView

    private var spSource: Spinner? = null
    private var tileVod: TextView? = null
    private var tileJsm: TextView? = null

    private val logLines = ArrayDeque<String>()
    private var downloading = false
    private var sourceReady = false
    private var sourceIndex = 0
    private var editingCustom = false
    private var lastMainFocus: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(if (isTvDevice) R.layout.activity_main_tv else R.layout.activity_main)
        } catch (t: Throwable) {
            android.util.Log.e("MainActivity", "tv layout inflate failed, fallback phone", t)
            setContentView(R.layout.activity_main)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                window.decorView.defaultFocusHighlightEnabled = false
            } catch (_: Throwable) {
            }
        }

        prefs = getSharedPreferences(AppConstants.PREFS_NAME, MODE_PRIVATE)
        try {
            bindViews()
        } catch (t: Throwable) {
            android.util.Log.e("MainActivity", "bindViews failed, fallback phone layout", t)
            setContentView(R.layout.activity_main)
            bindViews()
        }

        tvTitle.setText(if (isTvDevice) R.string.title_tv else R.string.title_phone)
        tvSubtitle.setText(R.string.subtitle)
        tvDevice.setText(if (isTvDevice) R.string.device_tv else R.string.device_phone)

        restoreSourceSelection()
        if (spSource != null) setupSourceSpinner()
        setupSourceTiles()
        applySourceUi()
        sourceReady = true

        refreshPermStatus()
        tvStatus.movementMethod = ScrollingMovementMethod()
        progress.isIndeterminate = false
        showBanner(R.drawable.bg_banner_idle, R.color.text_secondary, getString(R.string.banner_idle))

        downloader = XcctvSourceDownloader(this, this, AppConstants.DOWNLOAD_CONCURRENCY)

        btnStart.setOnClickListener { startDownload() }
        btnStop.setOnClickListener { stopDownload() }
        btnPerm.setOnClickListener { requestStoragePerm() }
        btnClear.setOnClickListener { clearInputUrl() }
        btnPaste.setOnClickListener { fillDefaultUrl() }
        etUrl.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) persistCurrentUrl()
        }
        cbBoot.isChecked = prefs.getBoolean(AppConstants.KEY_BOOT_LAUNCH, false)
        cbBoot.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(AppConstants.KEY_BOOT_LAUNCH, checked).apply()
        }

        cbAutoUpdate?.let { box ->
            box.isChecked = prefs.getBoolean(AppConstants.KEY_AUTO_UPDATE, false)
            box.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(AppConstants.KEY_AUTO_UPDATE, checked).apply()
                try {
                    if (checked) AutoUpdateScheduler.enable(this) else AutoUpdateScheduler.disable(this)
                } catch (t: Throwable) {
                    android.util.Log.e("MainActivity", "auto update toggle failed", t)
                }
            }
        }

        cbHttpServer?.let { box ->
            box.isChecked = prefs.getBoolean(AppConstants.KEY_HTTP_SERVER, false)
            box.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(AppConstants.KEY_HTTP_SERVER, checked).apply()
                try {
                    if (checked) HttpServerService.start(this) else HttpServerService.stop(this)
                } catch (t: Throwable) {
                    android.util.Log.e("MainActivity", "http server toggle failed", t)
                }
                updateHttpAddrDisplay()
            }
        }
        tvHttpAddr?.setOnClickListener { copyHttpAddr() }
        updateHttpAddrDisplay()

        if (isTvDevice) {
            try {
                setupTvControls()
            } catch (t: Throwable) {
                android.util.Log.e("MainActivity", "setupTvControls failed: ${t.message}", t)
            }
        }
        window.decorView.post { restoreBackgroundServices() }
    }

    private fun restoreBackgroundServices() {
        try {
            if (cbAutoUpdate?.isChecked == true) AutoUpdateScheduler.enable(this)
        } catch (t: Throwable) {
            android.util.Log.e("MainActivity", "restore auto update failed", t)
        }
        try {
            if (cbHttpServer?.isChecked == true) HttpServerService.start(this)
        } catch (t: Throwable) {
            android.util.Log.e("MainActivity", "restore http server failed", t)
        }
    }

    private fun bindViews() {
        etUrl = findViewById(R.id.et_url)
        customRow = findViewById(R.id.custom_row)
        btnClear = findViewById(R.id.btn_clear)
        btnPaste = findViewById(R.id.btn_paste)
        tvStatus = findViewById(R.id.tv_status)
        tvPermStatus = findViewById(R.id.tv_perm_status)
        tvSavePath = findViewById(R.id.tv_save_path)
        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)
        btnPerm = findViewById(R.id.btn_perm)
        cbBoot = findViewById(R.id.cb_boot)
        cbAutoUpdate = findViewById<CheckBox>(R.id.cb_auto_update)
        cbHttpServer = findViewById<CheckBox>(R.id.cb_http_server)
        tvHttpAddr = findViewById<TextView>(R.id.tv_http_addr)
        progress = findViewById(R.id.progress)
        tvTitle = findViewById(R.id.tv_title)
        tvSubtitle = findViewById(R.id.tv_subtitle)
        tvDevice = findViewById(R.id.tv_device)
        tvBanner = findViewById(R.id.tv_banner)
        spSource = findViewById<View>(R.id.sp_source) as? Spinner
        tileVod = findViewById<View>(R.id.tile_vod) as? TextView
        tileJsm = findViewById<View>(R.id.tile_jsm) as? TextView
    }

    private fun setupSourceSpinner() {
        val spinner = spSource ?: return
        val names = AppConstants.SOURCE_PRESETS.map { it.name }
        val adapter = ArrayAdapter(this, R.layout.item_spinner, names)
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown)
        spinner.adapter = adapter
        spinner.setSelection(sourceIndex)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!sourceReady) return
                selectSource(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun setupSourceTiles() {
        tileVod?.setOnClickListener { selectSource(0) }
        tileJsm?.setOnClickListener { selectSource(1) }
    }

    private fun restoreSourceSelection() {
        if (prefs.contains(AppConstants.KEY_SOURCE_INDEX)) {
            val raw = prefs.getInt(AppConstants.KEY_SOURCE_INDEX, 0)
            sourceIndex = if (raw in 0..AppConstants.SOURCE_PRESETS.lastIndex) raw else 0
            migrateLegacyCustomUrl()
            prefs.edit().putInt(AppConstants.KEY_SOURCE_INDEX, sourceIndex).apply()
            return
        }
        val last = prefs.getString(AppConstants.KEY_LAST_URL, null)
            ?: AppConstants.DEFAULT_SOURCE_URL
        val match = AppConstants.SOURCE_PRESETS.indexOfFirst { it.url == last }
        sourceIndex = if (match >= 0) match else 0
        if (match < 0 && last.isNotEmpty()) {
            prefs.edit().putString(AppConstants.urlKey(sourceIndex), last).apply()
        }
        prefs.edit().putInt(AppConstants.KEY_SOURCE_INDEX, sourceIndex).apply()
    }

    private fun migrateLegacyCustomUrl() {
        val legacy = prefs.getString("custom_source_url", null)?.trim().orEmpty()
        if (legacy.isEmpty()) return
        val key = AppConstants.urlKey(sourceIndex)
        if (prefs.getString(key, null).isNullOrBlank()) {
            prefs.edit().putString(key, legacy).remove("custom_source_url").apply()
        } else {
            prefs.edit().remove("custom_source_url").apply()
        }
    }

    private fun selectSource(index: Int) {
        persistCurrentUrl()
        sourceIndex = index.coerceIn(0, AppConstants.SOURCE_PRESETS.lastIndex)
        prefs.edit().putInt(AppConstants.KEY_SOURCE_INDEX, sourceIndex).apply()
        if (spSource?.selectedItemPosition != sourceIndex) {
            sourceReady = false
            spSource?.setSelection(sourceIndex)
            sourceReady = true
        }
        applySourceUi()
    }

    private fun applySourceUi() {
        customRow.visibility = View.VISIBLE
        etUrl.setText(resolvedUrl(sourceIndex))
        styleTile(tileVod, sourceIndex == 0)
        styleTile(tileJsm, sourceIndex == 1)
    }

    private fun resolvedUrl(index: Int): String {
        val preset = AppConstants.SOURCE_PRESETS.getOrNull(index)
            ?: return AppConstants.DEFAULT_SOURCE_URL
        val saved = prefs.getString(AppConstants.urlKey(index), null)
        return if (saved.isNullOrBlank()) preset.url else saved
    }

    private fun styleTile(tile: TextView?, selected: Boolean) {
        tile ?: return
        tile.setBackgroundResource(
            if (selected) R.drawable.bg_source_tile_selected else R.drawable.bg_source_tile
        )
        tile.setTextColor(
            ContextCompat.getColor(this, if (selected) R.color.cyan else R.color.text_primary)
        )
    }

    private fun persistCurrentUrl() {
        val typed = etUrl.text.toString().trim()
        val preset = AppConstants.SOURCE_PRESETS.getOrNull(sourceIndex) ?: return
        val key = AppConstants.urlKey(sourceIndex)
        if (typed.isEmpty() || typed == preset.url) {
            prefs.edit().remove(key).apply()
        } else {
            prefs.edit().putString(key, typed).apply()
        }
    }

    private fun fillDefaultUrl() {
        val preset = AppConstants.SOURCE_PRESETS.getOrNull(sourceIndex) ?: return
        prefs.edit().remove(AppConstants.urlKey(sourceIndex)).apply()
        etUrl.setText(preset.url)
        etUrl.requestFocus()
        if (isTvDevice) enterCustomEdit()
    }

    private fun clearInputUrl() {
        etUrl.setText("")
        etUrl.requestFocus()
        if (isTvDevice) enterCustomEdit()
    }

    private fun currentSourceUrl(): String {
        return etUrl.text.toString().trim()
    }

    private fun focusCurrentTile() {
        when (sourceIndex) {
            1 -> tileJsm?.requestFocus()
            else -> tileVod?.requestFocus()
        }
    }

    private fun setupTvControls() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            etUrl.showSoftInputOnFocus = false
        }
        etUrl.setOnEditorActionListener { _, actionId, event ->
            val enter = actionId == EditorInfo.IME_ACTION_DONE ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (enter) {
                exitCustomEdit()
                btnStart.requestFocus()
                true
            } else {
                false
            }
        }
        etUrl.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (editingCustom) {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    exitCustomEdit()
                    focusCurrentTile()
                    true
                } else {
                    false
                }
            } else {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        enterCustomEdit()
                        true
                    }
                    KeyEvent.KEYCODE_BACK -> {
                        focusCurrentTile()
                        true
                    }
                    else -> handleTvKey(keyCode)
                }
            }
        }
        tvStatus.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_LEFT -> {
                    (lastMainFocus ?: btnStart).requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    scrollLog(false)
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    scrollLog(true)
                    true
                }
                else -> false
            }
        }
        listOfNotNull(
            tileVod, tileJsm, btnPaste, btnClear, btnStart, btnStop, btnPerm,
            cbBoot, cbAutoUpdate, cbHttpServer, tvHttpAddr
        ).forEach { view ->
            view.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                handleTvKey(keyCode)
            }
        }
        btnStart.post { btnStart.requestFocus() }
    }

    private fun enterCustomEdit() {
        editingCustom = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            etUrl.showSoftInputOnFocus = true
        }
        etUrl.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(etUrl, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun exitCustomEdit() {
        editingCustom = false
        persistCurrentUrl()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etUrl.windowToken, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            etUrl.showSoftInputOnFocus = false
        }
    }

    private fun tvRows(): List<List<View>> {
        val rows = mutableListOf<List<View>>()
        if (downloading) {
            if (btnStop.isEnabled) rows.add(listOf<View>(btnStop))
            rows.add(listOfNotNull<View>(cbBoot, cbAutoUpdate, cbHttpServer))
            return rows
        }
        val tiles = listOfNotNull<View>(tileVod, tileJsm)
        if (tiles.isNotEmpty()) rows.add(tiles)
        rows.add(listOf<View>(etUrl, btnPaste, btnClear))
        if (btnStart.isEnabled) rows.add(listOf<View>(btnStart))
        if (btnStop.isEnabled) rows.add(listOf<View>(btnStop))
        val bottom = mutableListOf<View>()
        if (btnPerm.isEnabled) bottom.add(btnPerm)
        bottom.add(cbBoot)
        cbAutoUpdate?.let { bottom.add(it) }
        cbHttpServer?.let { bottom.add(it) }
        if (bottom.isNotEmpty()) rows.add(bottom)
        if (tvHttpAddr?.visibility == View.VISIBLE) {
            rows.add(listOfNotNull<View>(tvHttpAddr))
        }
        return rows
    }

    private fun findInRows(rows: List<List<View>>, target: View): Pair<Int, Int>? {
        rows.forEachIndexed { r, row ->
            val c = row.indexOf(target)
            if (c >= 0) return r to c
        }
        return null
    }

    private fun handleTvKey(keyCode: Int): Boolean {
        val focused = currentFocus ?: return false
        if (focused == tvStatus || editingCustom) return false
        val rows = tvRows()
        if (rows.isEmpty()) return false
        val pos = findInRows(rows, focused) ?: return false
        val (r, c) = pos
        val row = rows[r]
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                val next = rows[(r + 1) % rows.size]
                next[c.coerceAtMost(next.lastIndex)].requestFocus()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                val next = rows[(r - 1 + rows.size) % rows.size]
                next[c.coerceAtMost(next.lastIndex)].requestFocus()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (c > 0) {
                    row[c - 1].requestFocus()
                    return true
                }
                return false
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (c < row.lastIndex) {
                    row[c + 1].requestFocus()
                    return true
                }
                lastMainFocus = focused
                tvStatus.requestFocus()
                return true
            }
            else -> return false
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isTvDevice) return super.onKeyDown(keyCode, event)
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (editingCustom) {
                exitCustomEdit()
                focusCurrentTile()
                return true
            }
            if (currentFocus == tvStatus) {
                (lastMainFocus ?: btnStart).requestFocus()
                return true
            }
        }
        if (handleTvKey(keyCode)) return true
        return super.onKeyDown(keyCode, event)
    }

    private fun scrollLog(down: Boolean) {
        val delta = (tvStatus.height / 3).coerceAtLeast(48)
        val next = (tvStatus.scrollY + if (down) delta else -delta).coerceAtLeast(0)
        tvStatus.scrollTo(0, next)
    }

    override fun onPause() {
        persistCurrentUrl()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        refreshPermStatus()
        updateHttpAddrDisplay()
        if (isTvDevice && currentFocus == null) btnStart.post { btnStart.requestFocus() }
    }

    private fun updateHttpAddrDisplay() {
        val addr = tvHttpAddr ?: return
        if (cbHttpServer?.isChecked == true) {
            val ip = getLocalIpAddress()
            val url = "http://$ip:${AppConstants.HTTP_PORT}/vod.json"
            addr.text = "$url  (点击复制)"
            addr.visibility = View.VISIBLE
        } else {
            addr.visibility = View.GONE
        }
    }

    private fun copyHttpAddr() {
        val ip = getLocalIpAddress()
        val url = "http://$ip:${AppConstants.HTTP_PORT}/vod.json"
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("http_source_url", url))
        android.widget.Toast.makeText(this, "已复制: $url", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun getLocalIpAddress(): String {
        try {
            val en = java.net.NetworkInterface.getNetworkInterfaces()
            if (en != null) {
                for (ni in en) {
                    if (!ni.isUp || ni.isLoopback) continue
                    val addrs = ni.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            val host = addr.hostAddress ?: continue
                            if (host.startsWith("127.")) continue
                            return host
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return try {
            val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ipInt = wifiManager.connectionInfo.ipAddress
            if (ipInt == 0) return "127.0.0.1"
            String.format(
                "%d.%d.%d.%d",
                ipInt and 0xff,
                ipInt shr 8 and 0xff,
                ipInt shr 16 and 0xff,
                ipInt shr 24 and 0xff
            )
        } catch (_: Throwable) {
            "127.0.0.1"
        }
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
        if (requestCode == REQ_PERM) {
            refreshPermStatus()
            if (isTvDevice) (if (btnPerm.isEnabled) btnPerm else btnStart).requestFocus()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM) {
            refreshPermStatus()
            if (isTvDevice) (if (btnPerm.isEnabled) btnPerm else btnStart).requestFocus()
        }
    }

    private fun setSourceControlsEnabled(enabled: Boolean) {
        spSource?.isEnabled = enabled
        tileVod?.isEnabled = enabled
        tileJsm?.isEnabled = enabled
        etUrl.isEnabled = enabled
        btnPaste.isEnabled = enabled
        btnClear.isEnabled = enabled
        listOfNotNull(tileVod, tileJsm).forEach {
            it.isFocusable = enabled
            it.isClickable = enabled
        }
    }

    private fun startDownload() {
        persistCurrentUrl()
        val url = currentSourceUrl()
        if (url.isEmpty()) {
            appendLog(getString(R.string.log_empty_url))
            etUrl.requestFocus()
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
        setSourceControlsEnabled(false)
        progress.isIndeterminate = true
        progress.progress = 0
        progress.max = 100
        logLines.clear()
        tvStatus.text = ""
        tvStatus.scrollTo(0, 0)

        showBanner(R.drawable.bg_banner_loading, R.color.cyan, getString(R.string.banner_start))
        tvSavePath.text = getString(R.string.save_path, root.absolutePath)
        if (isTvDevice) btnStop.requestFocus()

        lifecycleScope.launch {
            val result = downloader.run(url)
            downloading = false
            btnStop.isEnabled = false
            setSourceControlsEnabled(true)
            progress.isIndeterminate = false
            refreshPermStatus()
            if (isTvDevice) btnStart.requestFocus()
            result.fold(
                onSuccess = { summary ->
                    progress.max = summary.discovered.coerceAtLeast(1)
                    progress.progress = (summary.downloaded + summary.skipped).coerceAtMost(progress.max)
                    tvSavePath.text = getString(R.string.save_path, summary.rootDir)
                    when {
                        summary.cancelled -> {
                            showBanner(R.drawable.bg_banner_fail, R.color.banner_fail, getString(R.string.banner_cancelled))
                            appendLog(getString(R.string.log_cancelled))
                        }
                        summary.failed.isNotEmpty() -> {
                            showBanner(R.drawable.bg_banner_fail, R.color.status_warn, getString(R.string.banner_partial))
                            appendLog(getString(R.string.log_all_done, summary.rootDir))
                        }
                        else -> {
                            showBanner(R.drawable.bg_banner_done, R.color.status_ok, getString(R.string.banner_done))
                            appendLog(getString(R.string.log_all_done, summary.rootDir))
                        }
                    }
                },
                onFailure = { err ->
                    progress.progress = 0
                    showBanner(R.drawable.bg_banner_fail, R.color.banner_fail, getString(R.string.banner_fail))
                    appendLog(getString(R.string.log_fail, err.message ?: ""))
                }
            )
        }
    }

    private fun stopDownload() {
        downloader.cancel()
        showBanner(R.drawable.bg_banner_fail, R.color.banner_fail, getString(R.string.banner_cancelled))
    }

    private fun showBanner(bg: Int, color: Int, text: String) {
        tvBanner.visibility = View.VISIBLE
        tvBanner.setBackgroundResource(bg)
        tvBanner.setTextColor(ContextCompat.getColor(this, color))
        tvBanner.text = text
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
            showBanner(
                R.drawable.bg_banner_loading,
                R.color.cyan,
                getString(R.string.banner_progress, current, total, file)
            )
        }
    }

    override fun onLog(msg: String) {
        runOnUiThread { appendLog(msg) }
    }
}
