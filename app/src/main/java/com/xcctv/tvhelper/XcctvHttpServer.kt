package com.xcctv.tvhelper

import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.net.URLConnection

/**
 * 本地 HTTP 文件服务器：监听 127.0.0.1:7890
 * 路由：GET /xcctv/{relPath} → 返回 rootDir/{relPath}
 *
 * 使用 NanoHTTPD（纯 Java，Android 兼容）
 */
class XcctvHttpServer(private val rootDir: File, port: Int = 7890) :
    NanoHTTPD("127.0.0.1", port) {

    init {
        // Android 上建议 wAiteForAllThreads=false，避免 shutdown 时阻塞
        isDaemon = true
        wAiteForAllThreads = false
    }

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri ?: "/"
        val rel = path.removePrefix("/xcctv/").removePrefix("/xcctv")
        if (rel.isEmpty() || rel.endsWith("/")) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "目录不支持列出")
        }
        val target = File(rootDir, rel)
        // 防止 ../ 穿越
        if (!target.canonicalPath.startsWith(rootDir.canonicalPath)) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "禁止访问")
        }
        if (!target.exists() || target.isDirectory) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "文件不存在: $rel")
        }
        return try {
            val mime = guessMime(target.name)
            val bytes = target.readBytes()
            newFixedLengthResponse(Response.Status.OK, mime, bytes)
        } catch (e: Exception) {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                "读取失败: ${e.message}"
            )
        }
    }

    override fun start(): Boolean {
        return try {
            super.start(SOCKET_READ_TIMEOUT, false) // no daemon=false 在 start 时阻塞；我们用自己的线程管理
        } catch (e: Exception) {
            false
        }
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
            name.endsWith(".m3u8", true) || name.endsWith(".m3u", true) -> "application/vnd.apple.mpegurl"
            name.endsWith(".ts", true) -> "video/mp2t"
            else -> URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
        }
    }
}
