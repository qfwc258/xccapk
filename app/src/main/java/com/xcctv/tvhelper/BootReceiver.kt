package com.xcctv.tvhelper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        context ?: return

        // 开机后恢复自动更新调度
        AutoUpdateScheduler.rescheduleIfNeeded(context)

        // 开机后恢复局域网 HTTP 源服务
        val prefs = context.getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(AppConstants.KEY_HTTP_SERVER, false)) {
            HttpServerService.start(context)
        }

        val enabled = prefs.getBoolean(AppConstants.KEY_BOOT_LAUNCH, false)
        if (!enabled) return
        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(launch)
    }
}
