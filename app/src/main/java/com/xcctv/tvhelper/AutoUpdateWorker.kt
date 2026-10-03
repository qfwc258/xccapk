package com.xcctv.tvhelper

import android.content.Context
import android.os.Environment
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 后台每日自动更新源
 * - 读取用户上次选择的源地址
 * - 无 UI 回调，静默下载
 * - 失败不重试打扰用户，下次周期再跑
 */
class AutoUpdateWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences(
            AppConstants.PREFS_NAME, Context.MODE_PRIVATE
        )

        // 1. 检查存储权限
        val hasPerm = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            applicationContext.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (!hasPerm) {
            // 权限丢了就不跑，等用户重新授权
            return@withContext Result.success()
        }

        // 2. 取上次用的源地址，没有就用默认
        val url = prefs.getString(AppConstants.KEY_LAST_URL, null)
            ?: AppConstants.DEFAULT_SOURCE_URL

        return@withContext try {
            val downloader = XcctvSourceDownloader(applicationContext, null)
            val ret = downloader.run(url)
            if (ret.isSuccess) {
                val sum = ret.getOrNull()
                if (sum != null && !sum.cancelled) {
                    android.util.Log.i(
                        "AutoUpdate",
                        "自动更新完成: 下载=${sum.downloaded} 跳过=${sum.skipped} 失败=${sum.failed.size}"
                    )
                }
            } else {
                android.util.Log.w(
                    "AutoUpdate",
                    "自动更新失败: ${ret.exceptionOrNull()?.message}"
                )
            }
            // 无论成败都 success，避免 WorkManager 反复重试打扰
            Result.success()
        } catch (e: Exception) {
            android.util.Log.w("AutoUpdate", "自动更新异常: ${e.message}")
            Result.success()
        }
    }
}
