package com.xcctv.tvhelper

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 自动更新任务调度：每日一次
 */
object AutoUpdateScheduler {

    fun enable(context: Context) {
        val req = PeriodicWorkRequestBuilder<AutoUpdateWorker>(1, TimeUnit.DAYS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            AppConstants.WORK_AUTO_UPDATE,
            ExistingPeriodicWorkPolicy.UPDATE,
            req
        )
    }

    fun disable(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(AppConstants.WORK_AUTO_UPDATE)
    }

    /** 开机后根据 prefs 恢复调度状态 */
    fun rescheduleIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(AppConstants.KEY_AUTO_UPDATE, false)) {
            enable(context)
        }
    }
}
