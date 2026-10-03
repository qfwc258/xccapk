package com.xcctv.tvhelper

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern
import kotlin.coroutines.coroutineContext

data class DownloadSummary(
    val discovered: Int,
    val skipped: Int,
    val downloaded: Int,
    val failed: List<String>,
    val rootDir: String,
    val cancelled: Boolean
)

interface DownloadProgressListener {
    fun onProgress(current: Int, total: Int, file: String)
    fun onLog(msg: String)
}

class XcctvSourceDownloader(
    private val context: Context,
    private val progressListener: DownloadProgressListener? = null,
    private val concurrency: Int = AppConstants.DOWNLOAD_CONCURRENCY
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val cancelled = AtomicBoolean(false)

    private val allPaths = ConcurrentHashMap.newKeySet<String>()
    private val spiderMd5Map = ConcurrentHashMap<String, String>()
    private val scannedLocalFiles = ConcurrentHashMap.newKeySet<String>()

    private val relPathPatterns: List<Pattern> = listOf(
        Pattern.compile("""["'](\.\/[A-Za-z0-9_\-./]+(?:\.[A-Za-z0-9]+)?)["']"""),
        Pattern.compile("""`(\.\/[A-Za-z0-9_\-./]+(?:\.[A-Za-z0-9]+)?)`""")
    )

    fun cancel() {
        cancelled.set(true)
    }

    suspend fun run(mainSourceUrl: String): Result<DownloadSummary> = withContext(Dispatchers.IO) {
        cancelled.set(false)
        allPaths.clear()
        spiderMd5Map.clear()
        scannedLocalFiles.clear()

        val rootDir = resolveRootDir(context)
        val skipped = AtomicInteger(0)
        val downloaded = AtomicInteger(0)
        val failed = ConcurrentHashMap.newKeySet<String>()

        try {
            progressListener?.onLog("保存目录: ${rootDir.absolutePath}")
            progressListener?.onLog("源地址: $mainSourceUrl")
            progressListener?.onLog("线程数: $concurrency")

            if (!rootDir.exists() || !rootDir.canWrite()) {
                return@withContext Result.failure(
                    Exception("保存目录不可写: ${rootDir.absolutePath}")
                )
            }

            checkCancel()
            progressListener?.onLog("开始下载主配置...")
            val mainJsonText = httpGetText(mainSourceUrl)

            val mainFileName = URL(mainSourceUrl).path.split("/").last().ifBlank { "vod.json" }
            val mainFile = File(rootDir, mainFileName)
            mainFile.parentFile?.mkdirs()
            mainFile.writeText(mainJsonText)
            progressListener?.onLog("主配置已保存: ${mainFile.absolutePath}")

            scanJsonElement(gson.fromJson(mainJsonText, JsonElement::class.java))
            scannedLocalFiles.add(mainFile.absolutePath)

            val baseUrlObj = URL(mainSourceUrl)
            val processedPaths = ConcurrentHashMap.newKeySet<String>()
            var round = 0

            while (round < AppConstants.MAX_SCAN_ROUNDS) {
                checkCancel()
                round++
                val pending = allPaths.filter { it !in processedPaths }
                if (pending.isEmpty()) {
                    progressListener?.onLog("无待下载文件，停止")
                    break
                }
                progressListener?.onLog("第 $round 轮，待处理: ${pending.size} 个")

                val sem = Semaphore(concurrency)
                val mutex = Mutex()

                coroutineScope {
                    pending.map { relPath ->
                        async(Dispatchers.IO) {
                            sem.acquire()
                            try {
                                checkCancel()
                                when (downloadOne(rootDir, baseUrlObj, relPath)) {
                                    "skip" -> skipped.incrementAndGet()
                                    "ok" -> downloaded.incrementAndGet()
                                    else -> failed.add(relPath)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                failed.add(relPath)
                                progressListener?.onLog("$relPath 失败: ${e.message}")
                            } finally {
                                processedPaths.add(relPath)
                                sem.release()
                            }
                            mutex.withLock {
                                progressListener?.onProgress(
                                    processedPaths.size,
                                    allPaths.size,
                                    relPath
                                )
                            }
                        }
                    }.awaitAll()
                }

                val beforeCount = allPaths.size
                val newFound = scanNewlyDownloadedSource(rootDir)
                if (newFound > 0) {
                    progressListener?.onLog("源码扫描新发现: $newFound 个")
                }
                if (allPaths.size == beforeCount) {
                    progressListener?.onLog("无新嵌套路径，停止递归")
                    break
                }
            }

            val failList = failed.toList().sorted()
            progressListener?.onLog(
                "完成。发现 ${allPaths.size}，下载 ${downloaded.get()}，跳过 ${skipped.get()}，失败 ${failList.size}"
            )
            if (failList.isNotEmpty()) {
                progressListener?.onLog("失败清单:")
                failList.forEach { progressListener?.onLog("  $it") }
            }

            Result.success(
                DownloadSummary(
                    discovered = allPaths.size,
                    skipped = skipped.get(),
                    downloaded = downloaded.get(),
                    failed = failList,
                    rootDir = rootDir.absolutePath,
                    cancelled = false
                )
            )
        } catch (ex: CancellationException) {
            progressListener?.onLog("已停止")
            Result.success(
                DownloadSummary(
                    discovered = allPaths.size,
                    skipped = skipped.get(),
                    downloaded = downloaded.get(),
                    failed = failed.toList().sorted(),
                    rootDir = rootDir.absolutePath,
                    cancelled = true
                )
            )
        } catch (ex: Exception) {
            progressListener?.onLog("出错: ${ex.message}")
            Result.failure(ex)
        }
    }

    private suspend fun checkCancel() {
        coroutineContext.ensureActive()
        if (cancelled.get()) throw CancellationException("已停止")
    }

    private suspend fun downloadOne(rootDir: File, baseUrlObj: URL, relPath: String): String {
        checkCancel()
        val localFile = File(rootDir, relPath)
        localFile.parentFile?.mkdirs()

        if (canSkip(localFile, relPath)) {
            return "skip"
        }

        val absUrl = buildChildUrl(baseUrlObj, relPath)
        var lastError: Exception? = null
        val attempts = AppConstants.MAX_RETRIES + 1
        repeat(attempts) { attempt ->
            checkCancel()
            try {
                localFile.writeBytes(httpGetBytes(absUrl))
                val expectMd5 = spiderMd5Map[relPath]
                if (!expectMd5.isNullOrEmpty()) {
                    val realMd5 = getFileMd5(localFile)
                    if (!expectMd5.equals(realMd5, ignoreCase = true)) {
                        throw Exception("MD5 不匹配")
                    }
                }
                return "ok"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (attempt < AppConstants.MAX_RETRIES) {
                    progressListener?.onLog("$relPath 重试 ${attempt + 1}/${AppConstants.MAX_RETRIES}: ${e.message}")
                }
            }
        }
        throw lastError ?: Exception("下载失败")
    }

    private fun canSkip(localFile: File, relPath: String): Boolean {
        if (!localFile.exists() || localFile.length() <= 0) return false
        val expectMd5 = spiderMd5Map[relPath]
        if (!expectMd5.isNullOrEmpty()) {
            return expectMd5.equals(getFileMd5(localFile), ignoreCase = true)
        }
        // 无 MD5 信息时无法判断内容是否变化，直接重新下载覆盖
        return false
    }

    private fun scanNewlyDownloadedSource(rootDir: File): Int {
        var newFound = 0
        rootDir.walkTopDown().forEach { file ->
            if (!file.isFile) return@forEach
            val absPath = file.absolutePath
            if (scannedLocalFiles.contains(absPath)) return@forEach

            val name = file.name.lowercase()
            val extOk = name.endsWith(".js") || name.endsWith(".json") ||
                name.endsWith(".py") || name.endsWith(".txt") ||
                name.endsWith(".ts") || name.endsWith(".mjs")
            if (!extOk) {
                scannedLocalFiles.add(absPath)
                return@forEach
            }

            try {
                val text = file.readText(Charsets.UTF_8)
                val baseDir = file.parentFile?.absolutePath
                    ?.let { it.substringAfter(rootDir.absolutePath).trimStart('/') }
                    ?: ""

                for (pat in relPathPatterns) {
                    val matcher = pat.matcher(text)
                    while (matcher.find()) {
                        val rawRel = matcher.group(1) ?: continue
                        val clean = rawRel.removePrefix("./").trim()
                        if (clean.isEmpty()) continue
                        if (clean.startsWith("http") || clean.contains("//")) continue
                        if (clean.contains(";md5;")) continue

                        val finalRel = if (baseDir.isNotEmpty()) "$baseDir/$clean" else clean
                        if (allPaths.add(finalRel)) newFound++
                    }
                }
            } catch (_: Exception) {
            }

            scannedLocalFiles.add(absPath)
        }
        return newFound
    }

    private fun buildChildUrl(base: URL, relPathClean: String): String {
        val withDotSlash = "./$relPathClean"
        return try {
            URL(base, withDotSlash).toString()
        } catch (_: Exception) {
            val path = base.path
            val slashIdx = path.lastIndexOf('/')
            val dir = if (slashIdx >= 0) path.substring(0, slashIdx + 1) else "/"
            "${base.protocol}://${base.host}$dir$relPathClean"
        }
    }

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
                    if (clean.isNotEmpty()) allPaths.add(clean)
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
        allPaths.add(clean)
        if (parts.size >= 2 && parts[1].isNotBlank()) {
            spiderMd5Map[clean] = parts[1].trim()
        }
    }

    private fun httpGetBytes(url: String): ByteArray {
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            return resp.body?.bytes() ?: throw Exception("空响应")
        }
    }

    private fun httpGetText(url: String): String =
        String(httpGetBytes(url), Charsets.UTF_8)

    private fun getFileMd5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        digest.update(file.readBytes())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
