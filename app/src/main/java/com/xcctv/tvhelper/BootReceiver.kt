package com.xcctv.tvhelper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        context ?: return

        val prefs = context.getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)
        try {
            AutoUpdateScheduler.rescheduleIfNeeded(context)
        } catch (t: Throwable) {
            android.util.Log.e("BootReceiver", "auto update reschedule failed", t)
        }
        try {
            if (prefs.getBoolean(AppConstants.KEY_HTTP_SERVER, false)) {
                HttpServerService.start(context)
            }
        } catch (t: Throwable) {
            android.util.Log.e("BootReceiver", "http server start failed", t)
        }

        val enabled = prefs.getBoolean(AppConstants.KEY_BOOT_LAUNCH, false)
        if (!enabled) return
        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(launch)
    }
}
