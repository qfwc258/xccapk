package com.xcctv.tvhelper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机自启：自动拉起 MainActivity
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            context?.let {
                val launch = Intent(it, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                it.startActivity(launch)
            }
        }
    }
}
