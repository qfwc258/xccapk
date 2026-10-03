package com.xcctv.tvhelper

import android.content.Context
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object AutoUpdateScheduler {

    @Volatile
    private var ready = false
    private val lock = Any()

    fun enable(context: Context) {
        if (!ensureWorkManager(context)) return
        val req = PeriodicWorkRequestBuilder<AutoUpdateWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            AppConstants.WORK_AUTO_UPDATE,
            ExistingPeriodicWorkPolicy.UPDATE,
            req
        )
    }

    fun disable(context: Context) {
        if (!ensureWorkManager(context)) return
        WorkManager.getInstance(context).cancelUniqueWork(AppConstants.WORK_AUTO_UPDATE)
    }

    fun rescheduleIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(AppConstants.KEY_AUTO_UPDATE, false)) {
            enable(context)
        }
    }

    private fun ensureWorkManager(context: Context): Boolean {
        if (ready) return true
        synchronized(lock) {
            if (ready) return true
            val app = context.applicationContext
            return try {
                WorkManager.initialize(app, Configuration.Builder().build())
                ready = true
                true
            } catch (t: Throwable) {
                try {
                    WorkManager.getInstance(app)
                    ready = true
                    true
                } catch (t2: Throwable) {
                    android.util.Log.e("AutoUpdate", "WorkManager unavailable", t2)
                    false
                }
            }
        }
    }
}
