package com.xcctv.tvhelper

import android.os.Environment
import com.google.gson.Gson
import com.google.gson.JsonElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URL
import java.security.MessageDigest

/** 统一计算外部存储根目录：优先 /sdcard/xcctv，fallback Download 目录 */
fun resolveRootDir(): File {
    val primary = File("/sdcard/xcctv")
    if (primary.canWrite() || primary.mkdirs()) return primary
    // fallback（Android 分区存储下应用私有目录可读可写）
    val fallback = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "xcctv")
    if (fallback.exists() || fallback.mkdirs()) return fallback
    return primary
}

// 进度回调接口
interface DownloadProgressListener {
    fun onProgress(current: Int, total: Int, file: String)
    fun onLog(msg: String)
}

class XcctvSourceDownloader(
    private val progressListener: DownloadProgressListener? = null
) {
    private val client = OkHttpClient()
    private val gson = Gson()
    val rootDir: File = resolveRootDir()

    private val relativePathSet = mutableSetOf<String>()
    private val spiderMd5Map = mutableMapOf<String, String>()

    suspend fun run(mainSourceUrl: String): Result<List<String>> = withContext(Dispatchers.IO) {
        return@withContext try {
            relativePathSet.clear()
            spiderMd5Map.clear()
            progressListener?.onLog("📁 保存目录: ${rootDir.absolutePath}")
            progressListener?.onLog("开始下载主配置...")

            val mainResp = httpGet(mainSourceUrl)
            val mainJsonText = mainResp.body?.string()
                ?: return@withContext Result.failure(Exception("主配置返回空"))
            val mainFileName = URL(mainSourceUrl).path.split("/").last()
            val mainLocalFile = File(rootDir, mainFileName)
            mainLocalFile.parentFile?.mkdirs()
            mainLocalFile.writeText(mainJsonText)
            progressListener?.onLog("✅主配置已保存: $mainLocalFile")

            // 扫描 JSON 收集所有相对路径
            scanJsonElement(gson.fromJson(mainJsonText, JsonElement::class.java))

            val list = relativePathSet.toList()
            val total = list.size
            progressListener?.onLog("🔍共发现 ${total} 个资源文件")
            val baseUrlObj = URL(mainSourceUrl)

            for ((idx, relPath) in list.withIndex()) {
                progressListener?.onProgress(idx + 1, total, relPath)
                val absUrl = URL(baseUrlObj, relPath).toString()
                val localFile = File(rootDir, relPath)
                localFile.parentFile?.mkdirs()

                val resp = httpGet(absUrl)
                val bodyBytes = resp.body?.bytes()
                resp.close()
                if (bodyBytes == null) {
                    progressListener?.onLog("⚠️$relPath 下载失败(空响应)")
                    continue
                }
                localFile.writeBytes(bodyBytes)

                // MD5 校验（仅 spider 声明过的）
                val expectMd5 = spiderMd5Map[relPath]
                if (!expectMd5.isNullOrEmpty()) {
                    val realMd5 = getFileMd5(localFile)
                    if (expectMd5.equals(realMd5, ignoreCase = true)) {
                        progressListener?.onLog("✅$relPath MD5校验通过")
                    } else {
                        progressListener?.onLog("❌$relPath MD5不匹配 期望:$expectMd5 实际:$realMd5")
                    }
                }
            }

            // 验证实际落盘文件数
            val actualCount = walkFiles(rootDir) - 1 // 减去主配置
            progressListener?.onLog("🎉全部完成！实际落盘 ${actualCount} 个资源")
            Result.success(list)
        } catch (ex: Exception) {
            Result.failure(ex)
        }
    }

    /** 递归统计目录下文件数 */
    private fun walkFiles(dir: File): Int {
        var n = 0
        dir.listFiles()?.forEach {
            if (it.isDirectory) n += walkFiles(it)
            else n++
        }
        return n
    }

    /** 解析 JSON 收集所有需要下载的相对路径（./xxx/yyy.ext） */
    private fun scanJsonElement(element: JsonElement) {
        when {
            element.isJsonObject -> {
                val obj = element.asJsonObject
                for ((k, v) in obj.entrySet()) {
                    if (k == "spider" && v.isJsonPrimitive) {
                        // spider 特殊格式："./jar/spider.jar;md5;xxxxxx"
                        // 只处理这一次，不再递归 v（避免 ;md5; 整串被当路径）
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
                // 必须以 "./" 开头，且不能包含 ";md5;"（spider 已单独处理过）
                if (str.startsWith("./") && !str.contains(";md5;")) {
                    // 规范化：移除开头的 "./" 得到干净的相对路径
                    val clean = str.removePrefix("./").trim()
                    if (clean.isNotEmpty()) {
                        relativePathSet.add(clean)
                    }
                }
            }
        }
    }

    /** 解析 spider 字段：支持带 md5 和不带 md5 两种 */
    private fun parseSpiderField(raw: String) {
        val parts = raw.split(";md5;")
        val pathRaw = parts[0].trim()
        if (!pathRaw.startsWith("./")) return
        val cleanPath = pathRaw.removePrefix("./")
        if (cleanPath.isEmpty()) return

        relativePathSet.add(cleanPath)
        if (parts.size >= 2 && parts[1].isNotBlank()) {
            spiderMd5Map[cleanPath] = parts[1].trim()
        }
    }

    private suspend fun httpGet(url: String) = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute()
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
