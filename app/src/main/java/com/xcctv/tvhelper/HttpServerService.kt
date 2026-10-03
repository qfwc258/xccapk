package com.xcctv.tvhelper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File

/**
 * 局域网 HTTP 源服务（前台 Service，保证后台不被杀）
 */
class HttpServerService : Service() {

    companion object {
        const val ACTION_START = "com.xcctv.tvhelper.HTTP_START"
        const val ACTION_STOP = "com.xcctv.tvhelper.HTTP_STOP"
        private const val NOTIF_ID = 2001
        private const val CHANNEL_ID = "http_server_channel"

        fun start(context: Context) {
            val intent = Intent(context, HttpServerService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, HttpServerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var httpServer: HttpFileServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_START -> startServer()
                ACTION_STOP -> {
                    stopServer()
                    stopSelf()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("HttpServerService", "启动失败: ${e.message}", e)
            stopServer()
            stopSelf()
        }
        return START_STICKY
    }

    private fun startServer() {
        if (httpServer != null) return
        val ip = getLocalIpAddress()
        val url = "http://$ip:${AppConstants.HTTP_PORT}/vod.json"
        startForeground(NOTIF_ID, buildNotification(url))

        val rootDir = File(AppConstants.ROOT_DIR)
        if (!rootDir.exists()) rootDir.mkdirs()
        httpServer = HttpFileServer(rootDir, AppConstants.HTTP_PORT).apply { startServer() }
    }

    private fun stopServer() {
        httpServer?.stopServer()
        httpServer = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun buildNotification(url: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "局域网源服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "XCCTV 局域网 HTTP 源共享服务"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("XCCTV 局域网源服务运行中")
            .setContentText("TVBox 填: $url")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun getLocalIpAddress(): String {
        return try {
            val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val ipInt = wifiManager.connectionInfo.ipAddress
            if (ipInt == 0) return "127.0.0.1"
            String.format(
                "%d.%d.%d.%d",
                ipInt and 0xff,
                ipInt shr 8 and 0xff,
                ipInt shr 16 and 0xff,
                ipInt shr 24 and 0xff
            )
        } catch (e: Exception) {
            "127.0.0.1"
        }
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }
}
