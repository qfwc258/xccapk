package com.xcctv.tvhelper

data class SourcePreset(val name: String, val url: String)

object AppConstants {
    const val PREFS_NAME = "xcctv_prefs"
    const val KEY_LAST_URL = "last_source_url"
    const val KEY_SOURCE_INDEX = "source_preset_index"
    const val KEY_BOOT_LAUNCH = "boot_launch_enabled"
    const val KEY_AUTO_UPDATE = "auto_update_enabled"
    const val WORK_AUTO_UPDATE = "auto_update_work"
    const val KEY_URL_PREFIX = "source_url_"
    const val ROOT_DIR = "/sdcard/xcctv"
    const val FALLBACK_DIR_NAME = "xcctv"
    const val DOWNLOAD_CONCURRENCY = 4
    const val MAX_SCAN_ROUNDS = 20
    const val MAX_RETRIES = 2
    const val MAX_LOG_LINES = 80
    const val DEFAULT_SOURCE_URL =
        "https://gh-proxy.org/https://raw.githubusercontent.com/qfwc258/xccapk/main/tv/vod.json"

    val SOURCE_PRESETS: List<SourcePreset> = listOf(
        SourcePreset(
            "点播 vod.json",
            "https://gh-proxy.org/https://raw.githubusercontent.com/qfwc258/xccapk/main/tv/vod.json"
        ),
        SourcePreset(
            "合集 jsm.json",
            "https://gh-proxy.org/https://raw.githubusercontent.com/qfwc258/xccapk/main/tv/jsm.json"
        )
    )

    fun urlKey(index: Int): String = KEY_URL_PREFIX + index
}
