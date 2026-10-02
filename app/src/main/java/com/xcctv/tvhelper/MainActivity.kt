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
        private const val PREFS = "xcctv_prefs"
        private const val KEY_LAST_URL = "last_source_url"
        private const val REQ_PERM = 1001

        private const val DEFAULT_TV_URL =
            "https://gh-proxy.org/https://raw.githubusercontent.com/qfwc258/xccapk/main/tv/vod.json"
        private const val DEFAULT_PHONE_URL =
            "https://gh-proxy.org/https://raw.githubusercontent.com/qfwc258/xccapk/main/tv/vod.json"
    }

    /**
     * 增强TV设备识别，适配魔百盒、UNT413等国产无Leanback盒子
     * 判定优先级：系统TV特征 > 设备型号关键词匹配 > 无触摸屏兜底
     */
    private val isTvDevice: Boolean by lazy {
        val pm = packageManager

        // 1.标准Android TV系统特征
        val hasLeanback = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val hasTvFeature = pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION)

        // 2.原生TV指纹特征
        val fingerprintTv = Build.FINGERPRINT.contains("google_tv", ignoreCase = true)
                || Build.FINGERPRINT.contains("androidtv", ignoreCase = true)

        // 3.国内盒子型号关键词库
        val tvBoxKeywords = listOf(
            "unt", "mibox", "mango", "tvbox", "box",
            "魔百盒", "创维", "海美迪", "泰捷", "当贝",
            "t95", "x96", "h96", "tx3", "tx6"
        )
        val modelMatchTv = tvBoxKeywords.any { Build.MODEL.contains(it, ignoreCase = true) }

        // 4.兜底：无触摸屏判定为盒子/TV
        val hasTouchScreen = pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)

        hasLeanback || hasTvFeature || fingerprintTv || modelMatchTv || !hasTouchScreen
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

    // 横幅下载计数（onProgress 回调累积）
    private var totalCount = 0
    private var doneCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        // Views
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

        val deviceTag = if (isTvDevice) "📺 TV端" else "📱 手机端"
        tvTitle.text = "$deviceTag  XCCTV助手"
        tvSubtitle.text = "源地址解析 · 4线程并行下载 · /sdcard/xcctv"

        val saved = prefs.getString(KEY_LAST_URL, null)
        etUrl.setText(saved ?: if (isTvDevice) DEFAULT_TV_URL else DEFAULT_PHONE_URL)

        // ✅ 权限状态刷新 + 自动申请
        refreshPermStatus()

        // ✅ TV 端遥控器焦点链
        if (isTvDevice) {
            setupTvFocus()
        }

        tvStatus.movementMethod = ScrollingMovementMethod()
        progress.visibility = View.INVISIBLE

        downloader = XcctvSourceDownloader(this)

        btnStart.setOnClickListener { startDownload() }

        // ✅ 清空 = 只清输入框，不清缓存
        btnClear.setOnClickListener {
            etUrl.setText("")
            etUrl.requestFocus()
            tvBanner.visibility = View.GONE
        }

        // ✅ 默认 = 恢复默认源地址
        btnFolder.setOnClickListener {
            val defaultUrl = if (isTvDevice) DEFAULT_TV_URL else DEFAULT_PHONE_URL
            etUrl.setText(defaultUrl)
            etUrl.setSelection(etUrl.text.length) // 光标跳到末尾
            etUrl.requestFocus()
        }

        btnPerm.setOnClickListener { requestStoragePermission() }
    }

    /** ✅ 启动 / onResume 时刷新权限状态条 */
    private fun refreshPermStatus() {
        val ok = hasStoragePerm()
        if (ok) {
            tvPermStatus.text = "✅ 已授权  /sdcard 可写"
            tvPermStatus.setTextColor(0xFF22C55E.toInt())
            btnPerm.text = "✓ 已授权"
            btnPerm.isEnabled = false
            btnStart.isEnabled = true
        } else {
            tvPermStatus.text = "⚠️ 未授权 /sdcard 写权限 → 点右侧按钮授权"
            tvPermStatus.setTextColor(0xFFF59E0B.toInt())
            btnPerm.text = "🔐 授权 /sdcard"
            btnPerm.isEnabled = true
            // 未授权时也允许用户输入源地址，但开始下载会提示错误
            btnStart.isEnabled = true
        }
    }

    /** ✅ 判断当前是否有 /sdcard 写权限 */
    private fun hasStoragePerm(): Boolean {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                // Android 11+：MANAGE_EXTERNAL_STORAGE
                Environment.isExternalStorageManager()
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                // Android10，manifest配置requestLegacyExternalStorage=true
                true
            }
            else -> {
                // Android9及以下
                checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            }
        }
    }

    /** ✅ 跳转系统"所有文件访问权限"设置页（Android 11+） */
    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivityForResult(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    },
                    REQ_PERM
                )
            } catch (_: Exception) {
                // 部分定制ROM回退到通用入口
                startActivityForResult(
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                    REQ_PERM
                )
            }
        } else {
            requestPermissions(
                arrayOf(
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    android.Manifest.permission.READ_EXTERNAL_STORAGE
                ),
                REQ_PERM
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermStatus()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PERM) refreshPermStatus()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM) refreshPermStatus()
    }

    // ========== TV 遥控器焦点 ==========
    private fun setupTvFocus() {
        val scale = resources.displayMetrics.density

        etUrl.isFocusable = true
        btnStart.isFocusable = true
        btnClear.isFocusable = true
        btnFolder.isFocusable = true
        btnPerm.isFocusable = true

        // 焦点方向链
        etUrl.nextFocusDownId = R.id.btn_start
        btnStart.nextFocusUpId = R.id.et_url
        btnStart.nextFocusDownId = R.id.btn_perm
        btnPerm.nextFocusUpId = R.id.btn_start
        btnPerm.nextFocusDownId = R.id.btn_clear
        btnClear.nextFocusUpId = R.id.btn_perm
        btnClear.nextFocusRightId = R.id.btn_folder
        btnFolder.nextFocusLeftId = R.id.btn_clear
        btnFolder.nextFocusUpId = R.id.btn_start

        // ✅ 给每个按钮应用 TV 焦点 selector + 放大动效 + 运行时文字颜色兜底
        val FOCUS_TEXT_COLOR = 0xFFFFFFFF.toInt()   // 焦点时：纯白
        val NORMAL_TEXT_COLOR = 0xFFE2E8F0.toInt()  // 普通时：浅灰蓝

        fun tvSelectable(
            v: View,
            focusBgRes: Int,
            minHeightDp: Int = 48,
            textSp: Float = 18f
        ) {
            (v as? Button)?.textSize = textSp
            v.minimumHeight = (minHeightDp * scale).toInt()
            v.setBackgroundResource(focusBgRes)
            // 默认文字颜色
            (v as? Button)?.setTextColor(NORMAL_TEXT_COLOR)

            v.setOnFocusChangeListener { view, has ->
                view.animate()
                    .scaleX(if (has) 1.12f else 1f)
                    .scaleY(if (has) 1.12f else 1f)
                    .translationZ(if (has) 8f * scale else 0f)
                    .setDuration(180)

                // ✅ 三重兜底：selector 背景 + activated 状态 + 运行时文字颜色
                view.isActivated = has
                if (view is Button) {
                    view.setTextColor(if (has) FOCUS_TEXT_COLOR else NORMAL_TEXT_COLOR)
                } else if (view is EditText && has) {
                    view.setTextColor(0xFFE2E8F0.toInt())
                    view.highlightColor = 0xFF22D3EE.toInt()
                }
            }
        }

        tvSelectable(etUrl, R.drawable.bg_input_tv, 56, 16f)
        tvSelectable(btnStart, R.drawable.bg_btn_primary_tv)
        tvSelectable(btnClear, R.drawable.bg_btn_ghost_tv)
        tvSelectable(btnFolder, R.drawable.bg_btn_ghost_tv)
        tvSelectable(btnPerm, R.drawable.bg_btn_primary_tv, 44, 16f)

        etUrl.requestFocus()
    }

    // ========== 目录打开 ==========
    private fun tryOpenFolderWithSystemApp(dir: java.io.File): Boolean {
        if (!dir.exists()) return false
        val candidates = listOf(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.fromFile(dir), "*/*")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent(Intent.ACTION_VIEW).apply {
                data = Uri.fromFile(dir)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        for (intent in candidates) {
            try {
                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(Intent.createChooser(intent, "打开文件夹"))
                    return true
                }
            } catch (_: Exception) { }
        }
        return false
    }

    // ========== 下载 ==========
    private fun startDownload() {
        if (!hasStoragePerm()) {
            tvStatus.append("\n🚫 请先授权 /sdcard 写权限（点上方 🔐 授权按钮）")
            scrollToBottom()
            requestStoragePermission()
            return
        }
        val url = etUrl.text.toString().trim()
        if (url.isEmpty()) {
            tvStatus.append("\n⚠️请先输入源地址")
            return
        }
        prefs.edit().putString(KEY_LAST_URL, url).apply()

        btnStart.isEnabled = false
        btnClear.isEnabled = false
        btnPerm.isEnabled = false
        progress.visibility = View.VISIBLE
        tvStatus.text = ""

        // ✅ 横幅：下载中
        totalCount = 0; doneCount = 0
        tvBanner.visibility = View.VISIBLE
        tvBanner.setBackgroundResource(R.drawable.bg_banner_loading)
        tvBanner.text = "⏳ 下载中..."

        scrollToBottom()

        lifecycleScope.launch {
            val ret = downloader.run(url)
            btnStart.isEnabled = true
            btnClear.isEnabled = true
            btnPerm.isEnabled = !hasStoragePerm()
            progress.visibility = View.INVISIBLE
            if (ret.isSuccess) {
                // ✅ 横幅：下载完成（绿色）
                tvBanner.setBackgroundResource(R.drawable.bg_banner_done)
                tvBanner.text = "🎉 下载完成  共 ${doneCount} 个文件  →  /sdcard/xcctv"
                tvStatus.append("\n🎉下载完成！去 /sdcard/xcctv 查看")
            } else {
                // ✅ 横幅：下载失败（红色）
                tvBanner.setBackgroundColor(0xFFDC2626.toInt())
                tvBanner.text = "❌ 下载失败: ${ret.exceptionOrNull()?.message?.take(60)}"
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
            totalCount = total
            doneCount = current
            progress.max = total.coerceAtLeast(1)
            progress.progress = current
            // ✅ 横幅实时更新进度
            tvBanner.text = "⏳ 下载中  $current / $total"
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
