package com.xcctv.tvhelper

import fi.iki.elonen.NanoHTTPD
import java.io.File

/**
 * 局域网 HTTP 文件共享服务（基于 NanoHTTPD，Android 兼容）
 * 把 /sdcard/xcctv 目录通过 HTTP 暴露，TVBox 可直接填 http://盒子IP:端口/vod.json
 */
class HttpFileServer(
    private val rootDir: File,
    port: Int
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val rawPath = session.uri.removePrefix("/").trimStart('/')
        val cleanPath = rawPath.replace("..", "")

        val target = if (cleanPath.isEmpty()) {
            File(rootDir, "vod.json") // 根路径默认返回 vod.json
        } else {
            File(rootDir, cleanPath)
        }

        if (!target.exists() || !target.isFile) {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "text/plain; charset=utf-8",
                "404 Not Found: $cleanPath"
            )
        }

        val mime = getMimeTypeForFile(target.name)
        val response = newFixedLengthResponse(
            Response.Status.OK,
            mime,
            target.inputStream(),
            target.length()
        )
        response.addHeader("Access-Control-Allow-Origin", "*")
        return response
    }

    fun startServer() {
        if (wasStarted()) return
        start(5000, true) // 守护线程，socket超时5秒，不阻塞调用线程
    }

    fun stopServer() {
        if (wasStarted()) stop()
    }
}
