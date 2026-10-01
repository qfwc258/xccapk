package com.xcctv.tvhelper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if(intent?.action == Intent.ACTION_BOOT_COMPLETED){
            context?.let {
                GlobalScope.launch(Dispatchers.IO) {
                    // 启动HTTP服务
                    val rootDir = it.filesDir.resolve("xcctv")
                    val server = XcctvHttpServer(rootDir)
                    server.start()
                }
            }
        }
    }
}
