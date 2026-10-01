package com.xcctv.tvhelper

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URL
import java.security.MessageDigest

// 进度回调接口
interface DownloadProgressListener {
    fun onProgress(current:Int, total:Int, file:String)
    fun onLog(msg:String)
}

class XcctvSourceDownloader(
    private val ctx: Context,
    private val progressListener: DownloadProgressListener? = null
) {
    private val client = OkHttpClient()
    private val gson = Gson()
    val rootDir = File(ctx.filesDir, "xcctv")
    private val relativePathSet = mutableSetOf<String>()
    private val spiderMd5Map = mutableMapOf<String,String>()

    init {
        if (!rootDir.exists()) rootDir.mkdirs()
    }

    suspend fun run(mainSourceUrl: String): Result<List<String>> = withContext(Dispatchers.IO) {
        return@withContext try {
            relativePathSet.clear()
            spiderMd5Map.clear()
            progressListener?.onLog("开始下载主配置...")
            val mainResp = httpGet(mainSourceUrl)
            val mainJsonText = mainResp.body?.string() ?: return@withContext Result.failure(Exception("主配置返回空"))
            val mainFileName = URL(mainSourceUrl).path.split("/").last()
            val mainLocalFile = File(rootDir, mainFileName)
            mainLocalFile.writeText(mainJsonText)
            progressListener?.onLog("主配置下载完成，开始解析JSON...")

            val jsonRoot = gson.fromJson(mainJsonText, JsonElement::class.java)
            scanJsonElement(jsonRoot)

            val list = relativePathSet.toList()
            val total = list.size
            val downloadedList = mutableListOf<String>()
            progressListener?.onLog("共发现${total}个资源文件")
            val baseUrlObj = URL(mainSourceUrl)

            for((idx, relPath) in list.withIndex()){
                progressListener?.onProgress(idx+1, total, relPath)
                val childAbsUrl = URL(baseUrlObj, relPath).toString()
                val localFile = File(rootDir, relPath)
                localFile.parentFile?.mkdirs()
                val resp = httpGet(childAbsUrl)
                localFile.writeBytes(resp.body?.bytes() ?: continue)
                resp.close()

                // MD5校验
                if(spiderMd5Map.containsKey(relPath)){
                    val expectMd5 = spiderMd5Map[relPath]
                    val realMd5 = getFileMd5(localFile)
                    if(expectMd5.equals(realMd5, ignoreCase = true)){
                        progressListener?.onLog("$relPath MD5校验通过")
                    }else{
                        progressListener?.onLog("$relPath MD5校验失败！预期:$expectMd5 实际:$realMd5")
                    }
                }
                downloadedList.add(relPath)
            }
            Result.success(downloadedList)
        } catch (ex: Exception) {
            Result.failure(ex)
        }
    }

    private fun scanJsonElement(element: JsonElement) {
        when {
            element.isJsonObject -> {
                val obj = element.asJsonObject
                for ((k, v) in obj.entrySet()) {
                    if (k == "spider" && v.isJsonPrimitive) {
                        val rawVal = v.asString
                        val parts = rawVal.split(";md5;")
                        val realPath = parts[0]
                        if(parts.size >=2 && realPath.startsWith("./")){
                            relativePathSet.add(realPath)
                            spiderMd5Map[realPath] = parts[1]
                        }
                    }
                    scanJsonElement(v)
                }
            }
            element.isJsonArray -> {
                val arr = element.asJsonArray
                for (item in arr) {
                    scanJsonElement(item)
                }
            }
            element.isJsonPrimitive -> {
                val str = element.asString
                if (str.startsWith("./")) {
                    relativePathSet.add(str)
                }
            }
        }
    }

    private suspend fun httpGet(url: String) = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).build()
        return@withContext client.newCall(req).execute()
    }

    fun clearCache() {
        rootDir.deleteRecursively()
        rootDir.mkdirs()
    }

    // 获取文件MD5
    private fun getFileMd5(file:File):String{
        val digest = MessageDigest.getInstance("MD5")
        val bytes = file.readBytes()
        digest.update(bytes,0,bytes.size)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
