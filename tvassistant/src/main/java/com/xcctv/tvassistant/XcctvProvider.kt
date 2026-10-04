package com.xcctv.tvassistant

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * file://xcctv/xxx 协议的 ContentProvider。
 * 所有文件从外部存储 /sdcard/xcctv 下读取（与 XcctvSourceDownloader 保持一致），
 * 供 FongMi 类 TV 播放器读取已下载的源。
 */
class XcctvProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        return true
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        // uri 形如 file://xcctv/jar/spider.jar → relPath = "jar/spider.jar"
        val relPath = uri.path?.removePrefix("/")?.trimStart('/')
            ?: throw FileNotFoundException()
        val root = resolveRootDir()
        val target = File(root, relPath)

        // 路径穿越防护
        if (!target.canonicalPath.startsWith(root.canonicalPath)) {
            throw FileNotFoundException("非法路径: $relPath")
        }
        if (!target.exists()) {
            throw FileNotFoundException("文件不存在: ${target.absolutePath}")
        }
        return ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun getType(uri: Uri): String? = "application/octet-stream"
}
