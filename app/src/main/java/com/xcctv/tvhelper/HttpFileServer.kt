package com.xcctv.tvhelper

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URLConnection

/**
 * 局域网 HTTP 文件共享服务
 * 把 /sdcard/xcctv 目录通过 HTTP 暴露，TVBox 可直接填 http://盒子IP:端口/vod.json
 */
class HttpFileServer(
    private val rootDir: File,
    private val port: Int
) {
    private var server: HttpServer? = null

    fun start() {
        if (server != null) return
        val srv = HttpServer.create(InetSocketAddress(port), 0)
        srv.createContext("/") { exchange -> handleRequest(exchange) }
        srv.executor = null
        srv.start()
        server = srv
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    private fun handleRequest(exchange: HttpExchange) {
        try {
            val rawPath = exchange.requestURI.path
            // 防止路径穿越
            val cleanPath = rawPath
                .removePrefix("/")
                .replace("..", "")
                .trimStart('/')

            val target = if (cleanPath.isEmpty()) {
                File(rootDir, "vod.json") // 根路径默认返回 vod.json
            } else {
                File(rootDir, cleanPath)
            }

            if (!target.exists() || !target.isFile) {
                val body = "404 Not Found: $cleanPath".toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(404, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
                return
            }

            val mime = URLConnection.guessContentTypeFromName(target.name) ?: "application/octet-stream"
            exchange.responseHeaders.set("Content-Type", mime)
            exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
            exchange.sendResponseHeaders(200, target.length())
            target.inputStream().use { input ->
                exchange.responseBody.use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            try {
                val body = "500 Error: ${e.message}".toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(500, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } catch (_: Exception) {
            }
        } finally {
            exchange.close()
        }
    }
}
