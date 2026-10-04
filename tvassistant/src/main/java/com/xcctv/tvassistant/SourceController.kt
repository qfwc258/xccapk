package com.xcctv.tvassistant

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 源下载的状态中枢（Kotlin 单例）。
 * 网页控制端 / 设备端 UI 通过它触发下载、读取进度与日志。
 * - start(ctx, url)：异步触发 XcctvSourceDownloader 下载到 /sdcard/xcctv；
 * - statusJson() / statusText()：供 Java 侧（WebServer、MainActivity）读取当前状态。
 */
object SourceController : DownloadProgressListener {

    @Volatile var status: String = "idle"   // idle | downloading | done | failed
    @Volatile var lastUrl: String = ""
    @Volatile var current: Int = 0
    @Volatile var total: Int = 0
    @Volatile var savedDir: String = "/sdcard/xcctv"

    private val logBuf = ArrayDeque<String>()
    private val lock = Any()

    /** 触发一次源下载（异步）。url 为空则忽略。 */
    fun start(_ctx: Context, url: String) {
        val u = url.trim()
        if (u.isEmpty()) return
        lastUrl = u
        savedDir = resolveRootDir().absolutePath
        status = "downloading"
        current = 0
        total = 0
        addLog("开始下载: $u")
        val downloader = XcctvSourceDownloader(this)
        CoroutineScope(Dispatchers.IO).launch {
            val ret = downloader.run(u)
            status = if (ret.isSuccess) "done" else "failed"
            addLog(if (ret.isSuccess) "🎉下载完成 → $savedDir" else "❌下载失败: ${ret.exceptionOrNull()?.message}")
        }
    }

    override fun onProgress(current: Int, total: Int, file: String) {
        this.current = current
        this.total = total
    }

    override fun onLog(msg: String) {
        addLog(msg)
    }

    private fun addLog(msg: String) {
        synchronized(lock) {
            logBuf.addLast(msg)
            while (logBuf.size > 200) logBuf.removeFirst()
        }
    }

    fun logTail(n: Int): String {
        synchronized(lock) {
            val skip = logBuf.size - n
            var i = 0
            return buildString {
                for (s in logBuf) {
                    if (i++ < skip) continue
                    append(s).append('\n')
                }
            }
        }
    }

    fun statusJson(): String {
        val o = JSONObject()
        o.put("status", status)
        o.put("url", lastUrl)
        o.put("current", current)
        o.put("total", total)
        o.put("dir", savedDir)
        o.put("log", logTail(15))
        return o.toString()
    }

    fun statusText(): String {
        return when (status) {
            "downloading" -> "下载中 $current/$total"
            "done" -> "已完成 → $savedDir"
            "failed" -> "失败"
            else -> "空闲"
        }
    }
}
