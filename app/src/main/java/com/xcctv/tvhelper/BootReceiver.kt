package com.xcctv.tvhelper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        context ?: return
        val prefs = context.getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(AppConstants.KEY_HTTP_SERVER, false)) {
            try {
                HttpServerService.start(context)
            } catch (t: Throwable) {
                android.util.Log.e("BootReceiver", "http start failed", t)
            }
        }
        if (!prefs.getBoolean(AppConstants.KEY_BOOT_LAUNCH, false)) return
        try {
            val launch = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(launch)
        } catch (t: Throwable) {
            android.util.Log.e("BootReceiver", "launch failed", t)
        }
    }
}
