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
import java.net.Inet4Address
import java.net.NetworkInterface

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
            try {
                if (Build.VERSION.SDK_INT >= 31) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                android.util.Log.e("HttpServerService", "startForegroundService failed", t)
                try {
                    context.startService(intent)
                } catch (t2: Throwable) {
                    android.util.Log.e("HttpServerService", "startService failed", t2)
                }
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, HttpServerService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (t: Throwable) {
                android.util.Log.e("HttpServerService", "stop failed", t)
            }
        }
    }

    private var httpServer: HttpFileServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_STOP -> {
                    stopServer()
                    stopSelf()
                }
                else -> startServer()
            }
        } catch (t: Throwable) {
            android.util.Log.e("HttpServerService", "onStartCommand failed", t)
            try {
                stopServer()
            } catch (_: Throwable) {
            }
            stopSelf()
        }
        return START_STICKY
    }

    private fun startServer() {
        if (Build.VERSION.SDK_INT >= 31) {
            val ip = getLocalIpAddress()
            val url = "http://$ip:${AppConstants.HTTP_PORT}/vod.json"
            try {
                startForeground(NOTIF_ID, buildNotification(url))
            } catch (t: Throwable) {
                android.util.Log.e("HttpServerService", "startForeground failed", t)
            }
        }
        if (httpServer != null) return
        val rootDir = resolveRootDir(this)
        if (!rootDir.exists()) rootDir.mkdirs()
        httpServer = HttpFileServer(rootDir, AppConstants.HTTP_PORT).apply { startServer() }
    }

    private fun stopServer() {
        try {
            httpServer?.stopServer()
        } catch (t: Throwable) {
            android.util.Log.e("HttpServerService", "stopServer failed", t)
        }
        httpServer = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Throwable) {
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
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun getLocalIpAddress(): String {
        try {
            val en = NetworkInterface.getNetworkInterfaces()
            if (en != null) {
                for (ni in en) {
                    if (!ni.isUp || ni.isLoopback) continue
                    val addrs = ni.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val host = addr.hostAddress ?: continue
                            if (host.startsWith("127.")) continue
                            return host
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return try {
            val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ipInt = wifiManager.connectionInfo.ipAddress
            if (ipInt == 0) return "127.0.0.1"
            String.format(
                "%d.%d.%d.%d",
                ipInt and 0xff,
                ipInt shr 8 and 0xff,
                ipInt shr 16 and 0xff,
                ipInt shr 24 and 0xff
            )
        } catch (_: Throwable) {
            "127.0.0.1"
        }
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }
}
