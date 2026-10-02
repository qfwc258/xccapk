package com.xcctv.tvhelper

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * 对外提供 file://xcctv/xxx 读取能力，文件来自 /sdcard/xcctv。
 */
class XcctvProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val relPath = uri.path?.trimStart('/') ?: throw FileNotFoundException()
        val root = resolveRootDir()
        val target = File(root, relPath)

        if (!target.canonicalPath.startsWith(root.canonicalPath + File.separator) &&
            target.canonicalPath != root.canonicalPath
        ) {
            throw FileNotFoundException("非法路径: $relPath")
        }
        if (!target.exists() || !target.isFile) {
            throw FileNotFoundException("文件不存在: ${target.absolutePath}")
        }
        return ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun getType(uri: Uri): String = "application/octet-stream"
}
