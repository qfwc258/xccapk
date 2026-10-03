package com.xcctv.tvhelper

import java.io.BufferedInputStream
import java.io.File
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.Locale

class HttpFileServer(
    private val rootDir: File,
    private val port: Int
) {
    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    fun startServer() {
        if (running) return
        running = true
        val ss = try {
            ServerSocket(port)
        } catch (t: Throwable) {
            running = false
            android.util.Log.e("HttpFileServer", "bind $port failed", t)
            return
        }
        serverSocket = ss
        acceptThread = Thread({
            while (running) {
                try {
                    val socket = ss.accept()
                    Thread({ handleClient(socket) }, "xcctv-http-client").apply {
                        isDaemon = true
                        start()
                    }
                } catch (_: SocketException) {
                    break
                } catch (t: Throwable) {
                    if (running) android.util.Log.e("HttpFileServer", "accept failed", t)
                }
            }
        }, "xcctv-http").apply {
            isDaemon = true
            start()
        }
    }

    fun stopServer() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        serverSocket = null
        acceptThread = null
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 8000
            socket.use { sock ->
                val input = BufferedInputStream(sock.getInputStream())
                val output = sock.getOutputStream()
                val header = readHeaders(input) ?: return
                val first = header.lineSequence().firstOrNull()?.trim().orEmpty()
                val parts = first.split(' ')
                if (parts.size < 2) {
                    writeText(output, 400, "Bad Request")
                    return
                }
                val method = parts[0].uppercase(Locale.US)
                if (method != "GET" && method != "HEAD") {
                    writeText(output, 405, "Method Not Allowed")
                    return
                }
                val rawPath = parts[1].substringBefore('?')
                val decoded = try {
                    URLDecoder.decode(rawPath, "UTF-8")
                } catch (_: Exception) {
                    rawPath
                }
                val clean = decoded.removePrefix("/").replace("..", "").trimStart('/')
                val target = if (clean.isEmpty()) File(rootDir, "vod.json") else File(rootDir, clean)
                val rootCanon = rootDir.canonicalFile
                val targetCanon = try {
                    target.canonicalFile
                } catch (_: Exception) {
                    target
                }
                if (!targetCanon.exists() || !targetCanon.isFile || !targetCanon.path.startsWith(rootCanon.path)) {
                    writeText(output, 404, "404 Not Found: $clean")
                    return
                }
                writeFile(output, targetCanon, sendBody = method == "GET")
            }
        } catch (t: Throwable) {
            android.util.Log.e("HttpFileServer", "client failed", t)
        }
    }

    private fun readHeaders(input: BufferedInputStream): String? {
        val buf = ByteArray(8192)
        var n = 0
        while (n < buf.size) {
            val b = input.read()
            if (b < 0) break
            buf[n++] = b.toByte()
            if (n >= 4 &&
                buf[n - 4] == '\r'.code.toByte() &&
                buf[n - 3] == '\n'.code.toByte() &&
                buf[n - 2] == '\r'.code.toByte() &&
                buf[n - 1] == '\n'.code.toByte()
            ) {
                return String(buf, 0, n, Charsets.ISO_8859_1)
            }
        }
        return if (n == 0) null else String(buf, 0, n, Charsets.ISO_8859_1)
    }

    private fun writeText(output: OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = if (code == 404) "Not Found" else if (code == 405) "Method Not Allowed" else if (code == 400) "Bad Request" else "OK"
        val head = "HTTP/1.1 $code $status\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun writeFile(output: OutputStream, file: File, sendBody: Boolean) {
        val mime = mimeOf(file.name)
        val head = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: $mime\r\n" +
            "Content-Length: ${file.length()}\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.US_ASCII))
        if (sendBody) {
            file.inputStream().use { it.copyTo(output) }
        }
        output.flush()
    }

    private fun mimeOf(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
            "json" -> "application/json; charset=utf-8"
            "js" -> "application/javascript; charset=utf-8"
            "xml" -> "text/xml; charset=utf-8"
            "html", "htm" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "txt" -> "text/plain; charset=utf-8"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "m3u8" -> "application/vnd.apple.mpegurl"
            "ts" -> "video/MP2T"
            else -> "application/octet-stream"
        }
    }
}
