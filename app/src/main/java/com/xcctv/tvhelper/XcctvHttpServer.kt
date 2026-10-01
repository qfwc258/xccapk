package com.xcctv.tvhelper

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.io.File
import java.net.URLConnection

/**
 * 本地 HTTP 文件服务器：监听 127.0.0.1:7890
 * 路由：GET /xcctv/{relPath} → 返回 rootDir/{relPath}
 *
 * 用 OkHttp MockWebServer 实现（项目已依赖 OkHttp，零额外架构）。
 */
class XcctvHttpServer(private val rootDir: File) {

    private var server: MockWebServer? = null

    fun start() {
        if (server != null) return
        val srv = MockWebServer()
        srv.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: "/"
                val rel = path.removePrefix("/xcctv/")
                if (rel.isEmpty() || rel.endsWith("/")) {
                    return MockResponse().setResponseCode(404).setBody("目录不支持列出")
                }
                val target = File(rootDir, rel)
                if (!target.exists() || target.isDirectory) {
                    return MockResponse().setResponseCode(404).setBody("文件不存在: $rel")
                }
                val mime = guessMime(target.name)
                val body = Buffer().write(target.readBytes())
                return MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", mime)
                    .addHeader("Content-Length", target.length())
                    .setBody(body.readUtf8())
            }
        }
        srv.start(7890)
        server = srv
    }

    fun stop() {
        try { server?.shutdown() } catch (_: Exception) {}
        server = null
    }

    private fun guessMime(name: String): String {
        return when {
            name.endsWith(".json", true) || name.endsWith(".txt", true) -> "application/json; charset=utf-8"
            name.endsWith(".xml", true) -> "application/xml; charset=utf-8"
            name.endsWith(".js", true) -> "application/javascript"
            name.endsWith(".css", true) -> "text/css"
            name.endsWith(".html", true) || name.endsWith(".htm", true) -> "text/html; charset=utf-8"
            name.endsWith(".png", true) -> "image/png"
            name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
            name.endsWith(".gif", true) -> "image/gif"
            name.endsWith(".webp", true) -> "image/webp"
            name.endsWith(".ico", true) -> "image/x-icon"
            name.endsWith(".apk", true) -> "application/vnd.android.package-archive"
            else -> URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
        }
    }
}
