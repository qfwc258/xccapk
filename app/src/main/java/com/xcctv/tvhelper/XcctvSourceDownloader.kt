package com.xcctv.tvhelper

import android.os.Environment
import com.google.gson.Gson
import com.google.gson.JsonElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 外部存储根目录选择：依次探测以下位置
 *   1. /sdcard/xcctv（用户期望路径，直观）
 *   2. /storage/emulated/0/xcctv（等价别名）
 *   3. Environment.DIRECTORY_DOWNLOADS/xcctv（公共下载目录，所有 Android 版本必可写）
 * 规则：创建 → 写入测试文件 → 成功即选，全部失败回退到 app 私有 filesDir。
 */
fun resolveRootDir(): File {
    val candidates = listOf(
        File("/sdcard/xcctv"),
        File("/storage/emulated/0/xcctv"),
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "xcctv"),
    )
    for (dir in candidates) {
        try {
            if (dir.exists() || dir.mkdirs()) {
                if (dir.canWrite()) {
                    return dir
                }
            }
        } catch (_: Exception) { /* 下一个 */ }
    }
    // 兜底：app 私有目录（保证一定可写）
    return File(android.os.Environment.getDataDirectory(), "/data/data/com.xcctv.tvhelper/files/xcctv").apply {
        if (!exists()) mkdirs()
    }
}

// 进度回调接口
interface DownloadProgressListener {
    fun onProgress(current: Int, total: Int, file: String)
    fun onLog(msg: String)
}

/**
 * 下载器：4 线程并行 + 路径递归扫描 + URL 重写
 */
class XcctvSourceDownloader(
    private val progressListener: DownloadProgressListener? = null,
    private val concurrency: Int = 4 // ✅ 4 线程并行
) {
    // ✅ 连接池 + 超时配置，让并行下载更快
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    val rootDir: File = resolveRootDir()

    private val relativePathSet = ConcurrentHashMap.newKeySet<String>()
    private val spiderMd5Map = ConcurrentHashMap<String, String>()

    // 🔽 对外入口：解析 → 下载（并行）
    suspend fun run(mainSourceUrl: String): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            relativePathSet.clear()
            spiderMd5Map.clear()

            progressListener?.onLog("📁 保存目录: ${rootDir.absolutePath}")
            progressListener?.onLog("🔗 源地址: $mainSourceUrl")
            progressListener?.onLog("🌐 线程数: $concurrency")
            progressListener?.onLog("开始下载主配置...")

            // 1. 确保目录存在并创建
            if (!rootDir.exists()) {
                rootDir.mkdirs()
                if (!rootDir.exists()) {
                    return@withContext Result.failure(
                        Exception("无法创建保存目录: ${rootDir.absolutePath}，请检查存储权限")
                    )
                }
            }
            if (!rootDir.canWrite()) {
                return@withContext Result.failure(
                    Exception("保存目录不可写: ${rootDir.absolutePath}，请授予存储权限")
                )
            }

            // 2. 下载主配置
            val mainJsonText = httpGet(mainSourceUrl)
                .body?.string()
                ?: return@withContext Result.failure(Exception("主配置返回空"))

            val mainFileName = URL(mainSourceUrl).path.split("/").last()
            val mainFile = File(rootDir, mainFileName)
            mainFile.parentFile?.mkdirs()
            mainFile.writeText(mainJsonText)
            progressListener?.onLog("✅主配置已保存: ${mainFile.absolutePath}")

            // 2. 递归扫描 JSON 里所有 ./ 相对路径
            scanJsonElement(gson.fromJson(mainJsonText, JsonElement::class.java))
            val baseUrlObj = URL(mainSourceUrl)
            val allPaths = relativePathSet.toList()

            progressListener?.onLog("🔍共发现 ${allPaths.size} 个资源文件")
            if (allPaths.isEmpty()) {
                return@withContext Result.success(emptyList())
            }

            // 3. ✅ 并行下载（信号量控制并发数）
            var completed = 0
            val mutex = Mutex()
            val sem = kotlinx.coroutines.sync.Semaphore(concurrency)

            coroutineScope {
                allPaths.map { relPath ->
                    async(Dispatchers.IO) {
                        sem.acquire()
                        try {
                            downloadOne(baseUrlObj, relPath)
                        } catch (e: Exception) {
                            progressListener?.onLog("❌$relPath 下载失败: ${e.message}")
                        } finally {
                            sem.release()
                        }
                        // 进度合并（并发安全）
                        mutex.withLock {
                            completed++
                            progressListener?.onProgress(completed, allPaths.size, relPath)
                        }
                    }
                }.awaitAll()
            }

            // 4. 落盘文件数校验
            val actualCount = walkFiles(rootDir) - 1 // 减去主配置
            val failed = allPaths.size - actualCount
            if (failed > 0) {
                progressListener?.onLog("⚠️ 有 $failed 个文件可能未落盘")
            }
            progressListener?.onLog("🎉完成！实际落盘 ${actualCount} 个资源")

            Result.success(allPaths)
        } catch (ex: Exception) {
            progressListener?.onLog("💥出错: ${ex.stackTraceToString().take(300)}")
            Result.failure(ex)
        }
    }

    private suspend fun downloadOne(baseUrlObj: URL, relPath: String) {
        // ✅ URL 重写加固：遇到 jsdelivr/gh-proxy 等代理也要正确解析子路径
        val absUrl = buildChildUrl(baseUrlObj, relPath)
        val localFile = File(rootDir, relPath)
        localFile.parentFile?.mkdirs()

        val body = httpGet(absUrl).body?.bytes()
            ?: throw Exception("空响应")
        localFile.writeBytes(body)

        // MD5 校验（spider 声明过的）
        val expectMd5 = spiderMd5Map[relPath]
        if (!expectMd5.isNullOrEmpty()) {
            val realMd5 = getFileMd5(localFile)
            if (expectMd5.equals(realMd5, ignoreCase = true)) {
                progressListener?.onLog("✅$relPath MD5✓")
            } else {
                progressListener?.onLog("❌$relPath MD5不匹配")
            }
        }
    }

    /** ✅ URL 相对路径解析加固：URL(base, "./jar/x.jar") 在代理 URL 上也能正确取到子目录 */
    private fun buildChildUrl(base: URL, relPathClean: String): String {
        // relPathClean 已经 removePrefix("./")，但 URL.resolve 需要它
        val withDotSlash = "./$relPathClean"
        return try {
            URL(base, withDotSlash).toString()
        } catch (e: Exception) {
            // fallback：手动拼
            val path = base.path
            val slashIdx = path.lastIndexOf('/')
            val dir = if (slashIdx >= 0) path.substring(0, slashIdx + 1) else "/"
            "${base.protocol}://${base.host}${dir}${relPathClean}"
        }
    }

    // ==================== JSON 扫描 ====================

    private fun scanJsonElement(element: JsonElement) {
        when {
            element.isJsonObject -> {
                for ((k, v) in element.asJsonObject.entrySet()) {
                    if (k == "spider" && v.isJsonPrimitive) {
                        parseSpiderField(v.asString)
                    } else {
                        scanJsonElement(v)
                    }
                }
            }
            element.isJsonArray -> {
                for (item in element.asJsonArray) scanJsonElement(item)
            }
            element.isJsonPrimitive -> {
                val str = element.asString
                if (str.startsWith("./") && !str.contains(";md5;")) {
                    val clean = str.removePrefix("./").trim()
                    if (clean.isNotEmpty()) relativePathSet.add(clean)
                }
            }
        }
    }

    private fun parseSpiderField(raw: String) {
        val parts = raw.split(";md5;")
        val pathRaw = parts[0].trim()
        if (!pathRaw.startsWith("./")) return
        val clean = pathRaw.removePrefix("./")
        if (clean.isEmpty()) return
        relativePathSet.add(clean)
        if (parts.size >= 2 && parts[1].isNotBlank()) {
            spiderMd5Map[clean] = parts[1].trim()
        }
    }

    private suspend fun httpGet(url: String) = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).build()).execute()
    }

    private fun walkFiles(dir: File): Int {
        var n = 0
        dir.listFiles()?.forEach {
            if (it.isDirectory) n += walkFiles(it) else n++
        }
        return n
    }

    fun clearCache() {
        rootDir.deleteRecursively()
        rootDir.mkdirs()
    }

    private fun getFileMd5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        val bytes = file.readBytes()
        digest.update(bytes, 0, bytes.size)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
