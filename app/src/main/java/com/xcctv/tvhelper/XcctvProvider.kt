package com.xcctv.tvhelper

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

class XcctvProvider : ContentProvider() {
    private lateinit var uriMatcher: UriMatcher
    companion object {
        const val AUTHORITY = "com.xcctv.tvhelper.provider"
        const val CODE_FILE = 1
    }

    override fun onCreate(): Boolean {
        uriMatcher = UriMatcher(UriMatcher.NO_MATCH)
        uriMatcher.addURI(AUTHORITY, "*", CODE_FILE)
        return true
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val relPath = uri.path?.removePrefix("/") ?: throw FileNotFoundException()
        val root = context?.filesDir ?: throw FileNotFoundException()
        val target = File(root, "xcctv/$relPath")
        if (!target.exists()) throw FileNotFoundException("文件不存在: $relPath")
        return ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun getType(uri: Uri): String? = "application/octet-stream"
}
