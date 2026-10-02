package com.xcctv.tvhelper

import java.io.File

fun resolveRootDir(): File {
    val dir = File(AppConstants.ROOT_DIR)
    if (!dir.exists()) dir.mkdirs()
    return dir
}
