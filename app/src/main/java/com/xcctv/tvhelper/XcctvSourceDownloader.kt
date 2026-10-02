package com.xcctv.tvhelper

import android.os.Environment
import com.google.gson.Gson
import com.google.gson.JsonElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import java.util.regex.Pattern

/**
 * 固定写 /sdcard/xcctv —— 权限由 MainActivity 提前通过 MANAGE_EXTERNAL_STORAGE 申请。
 * 如果分区存储拦住了（未授权），直接返回 null 让上层感知并提示用户。
 */
fun resolveRootDir(): File {
    val dir = File("/sdcard/xcctv")
    if (!dir.exists()) dir.mkdirs()
    return dir
}

// 进度回调接口
interface DownloadProgressListener {
    fun onProgress(current: Int, total: Int, file: String)
    fun onLog(msg: String)
}

/**
 * 下载器：
 *  - 4 线程并行
 *  - JSON 相对路径扫描（主配置 spider + 所有 "./xxx"）
 *  - ✅ 对已下载的 JS/JSON 文件，**继续扫源码里的 ./xxx 嵌套路径**，while 循环直到无新文件
 *  - URL 相对路径重写（gh-proxy/jsdelivr 代理兼容）
 */
class XcctvSourceDownloader(
    private val progressListener: DownloadProgressListener? = null,
    private val concurrency: Int = 4
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    val rootDir: File = resolveRootDir()

    // 所有已收集的相对路径（干净版，不带 "./" 头）
    private val allPaths = ConcurrentHashMap.newKeySet<String>()
    private val spiderMd5Map = ConcurrentHashMap<String, String>()
    // 已经过源码扫描的本地文件（避免重复扫）
    private val scannedLocalFiles = ConcurrentHashMap.newKeySet<String>()

    // 匹配 JS / Python / JSON 源码里的相对路径：
    //   import xx from "./lib.js"
    //   require("./lib.js")
    //   from "./lib.js"
    //   "./jar/spider.jar"
    private val REL_PATH_PATTERNS: List<Pattern> = listOf(
        Pattern.compile("""["'](\.\/[A-Za-z0-9_\-./]+(?:\.[A-Za-z0-9]+)?)["']"""),
        Pattern.compile("""`(\.\/[A-Za-z0-9_\-./]+(?:\.[A-Za-z0-9]+)?)`"""),
    )

    // 🔽 对外入口
    suspend fun run(mainSourceUrl: String): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            allPaths.clear()
            spiderMd5Map.clear()
            scannedLocalFiles.clear()

            progressListener?.onLog("📁 保存目录: ${rootDir.absolutePath}")
            progressListener?.onLog("🔗 源地址: $mainSourceUrl")
            progressListener?.onLog("🌐 线程数: $concurrency")

            // 1. 目录检查
            if (!rootDir.exists() || !rootDir.canWrite()) {
                return@withContext Result.failure(
                    Exception("保存目录不可写: ${rootDir.absolutePath}\n请先授予 /sdcard 全部文件访问权限")
                )
            }

            // 2. 下载主配置
            progressListener?.onLog("开始下载主配置...")
            val mainResp = httpGet(mainSourceUrl)
            val mainJsonText = mainResp.body?.string()
                ?: return@withContext Result.failure(Exception("主配置返回空"))

            val mainFileName = URL(mainSourceUrl).path.split("/").last()
            val mainFile = File(rootDir, mainFileName)
            mainFile.parentFile?.mkdirs()
            mainFile.writeText(mainJsonText)
            progressListener?.onLog("✅主配置已保存: ${mainFile.absolutePath}")

            // 3. 第一轮：扫描 JSON 里的 ./xxx
            scanJsonElement(gson.fromJson(mainJsonText, JsonElement::class.java))
            scannedLocalFiles.add(mainFile.absolutePath) // 主配置扫过了

            val baseUrlObj = URL(mainSourceUrl)
            var round = 0

            // 4. ✅ while 循环：下载 → 扫源码 → 发现新路径 → 继续
            while (true) {
                round++
                val pending = allPaths.toList()
                progressListener?.onLog("🔄 第 $round 轮，待下载/源码扫描: ${pending.size} 个")

                // 4a. 并行下载当前所有 pending
                val sem = Semaphore(concurrency)
                val mutex = Mutex()
                var completed = 0

                coroutineScope {
                    pending.map { relPath ->
                        async(Dispatchers.IO) {
                            sem.acquire()
                            try {
                                downloadOne(baseUrlObj, relPath)
                            } catch (e: Exception) {
                                progressListener?.onLog("❌$relPath 下载失败: ${e.message}")
                            } finally {
                                sem.release()
                            }
                            mutex.withLock {
                                completed++
                                progressListener?.onProgress(completed, pending.size, relPath)
                            }
                        }
                    }.awaitAll()
                }

                // 4b. ✅ 扫本轮新下载文件的源码，提取里面的 ./xxx 嵌套路径
                val beforeCount = allPaths.size
                val newFound = scanNewlyDownloadedSource()
                progressListener?.onLog("   ↳ 源码扫描新发现: $newFound 个（本轮前 $beforeCount）")

                if (allPaths.size == beforeCount) {
                    // 没有新路径了 → 退出
                    progressListener?.onLog("🛑 无新嵌套路径，停止递归")
                    break
                }
            }

            // 5. 落盘文件数校验
            val actualCount = walkFiles(rootDir) - 1 // 减去主配置
            val failed = allPaths.size - actualCount
            if (failed > 0) {
                progressListener?.onLog("⚠️ 有 $failed 个文件可能未落盘")
            }
            progressListener?.onLog("🎉完成！共发现 ${allPaths.size} 个，实际落盘 ${actualCount} 个")

            Result.success(allPaths.toList())
        } catch (ex: Exception) {
            progressListener?.onLog("💥出错: ${ex.stackTraceToString().take(400)}")
            Result.failure(ex)
        }
    }

    // ========== 单个文件下载 ==========
    private suspend fun downloadOne(baseUrlObj: URL, relPath: String) {
        val absUrl = buildChildUrl(baseUrlObj, relPath)
        val localFile = File(rootDir, relPath)
        localFile.parentFile?.mkdirs()

        val body = httpGet(absUrl).body?.bytes()
            ?: throw Exception("空响应")
        localFile.writeBytes(body)

        // MD5 校验
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

    // ========== ✅ 核心：扫描本轮新下载文件的源码，收集 ./xxx 嵌套路径 ==========
    private fun scanNewlyDownloadedSource(): Int {
        var newFound = 0
        rootDir.walkTopDown().forEach { file ->
            if (!file.isFile) return@forEach
            val absPath = file.absolutePath
            if (scannedLocalFiles.contains(absPath)) return@forEach

            // 只扫文本类文件（JS / JSON / PY / JAR 的入口清单等）
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
                // 找当前文件所在目录作为相对路径基准
                val baseDir = file.parentFile?.absolutePath?.let { it.substringAfter(rootDir.absolutePath).trimStart('/') } ?: ""

                for (pat in REL_PATH_PATTERNS) {
                    val m = pat.matcher(text)
                    while (m.find()) {
                        val rawRel = m.group(1) ?: continue
                        // 去掉开头的 "./"
                        val clean = rawRel.removePrefix("./").trim()
                        if (clean.isEmpty()) continue
                        // 过滤绝对 URL 或明显不是文件名的
                        if (clean.startsWith("http") || clean.contains("//")) continue
                        if (clean.contains(";md5;")) continue

                        // 相对路径：如果 baseDir 不为空，前面要拼上它（drpy2.min.js 在 lib/ 下，它的 ./uri.min.js → lib/uri.min.js）
                        val finalRel = if (baseDir.isNotEmpty()) "$baseDir/$clean" else clean
                        if (allPaths.add(finalRel)) {
                            newFound++
                            progressListener?.onLog("   ➕ 源码发现: $finalRel")
                        }
                    }
                }
            } catch (_: Exception) { /* 二进制文件或编码问题，跳过 */ }

            scannedLocalFiles.add(absPath)
        }
        return newFound
    }

    // ========== URL 重写加固 ==========
    private fun buildChildUrl(base: URL, relPathClean: String): String {
        val withDotSlash = "./$relPathClean"
        return try {
            URL(base, withDotSlash).toString()
        } catch (e: Exception) {
            val path = base.path
            val slashIdx = path.lastIndexOf('/')
            val dir = if (slashIdx >= 0) path.substring(0, slashIdx + 1) else "/"
            "${base.protocol}://${base.host}${dir}${relPathClean}"
        }
    }

    // ========== JSON 扫描 ==========
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

    // ========== HTTP ==========
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
