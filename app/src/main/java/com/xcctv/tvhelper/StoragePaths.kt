package com.xcctv.tvhelper

import android.content.Context
import java.io.File

fun resolveRootDir(context: Context): File {
    val primary = File(AppConstants.ROOT_DIR)
    if (ensureWritable(primary)) return primary

    val ext = context.getExternalFilesDir(null)
    if (ext != null) {
        val fallback = File(ext, AppConstants.FALLBACK_DIR_NAME)
        if (ensureWritable(fallback)) return fallback
    }

    val internal = File(context.filesDir, AppConstants.FALLBACK_DIR_NAME)
    ensureWritable(internal)
    return internal
}

fun isPrimaryRoot(dir: File): Boolean = dir.absolutePath == AppConstants.ROOT_DIR

private fun ensureWritable(dir: File): Boolean {
    return try {
        if (!dir.exists()) dir.mkdirs()
        dir.exists() && dir.canWrite()
    } catch (_: Exception) {
        false
    }
}
