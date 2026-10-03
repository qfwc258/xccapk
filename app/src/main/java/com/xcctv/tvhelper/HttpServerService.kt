package com.xcctv.tvhelper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
            val app = context.applicationContext
            val intent = Intent(app, HttpServerService::class.java).apply {
                action = ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    app.startForegroundService(intent)
                } else {
                    app.startService(intent)
                }
            } catch (t: Throwable) {
                android.util.Log.e("HttpServerService", "startForegroundService failed", t)
                try {
                    app.startService(intent)
                } catch (t2: Throwable) {
                    android.util.Log.e("HttpServerService", "startService failed", t2)
                }
            }
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, HttpServerService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                app.startService(intent)
            } catch (t: Throwable) {
                android.util.Log.e("HttpServerService", "stop failed", t)
            }
        }

        fun isEnabled(context: Context): Boolean {
            return context.applicationContext
                .getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(AppConstants.KEY_HTTP_SERVER, false)
        }
    }

    private var httpServer: HttpFileServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_STOP -> {
                    promoteForeground()
                    stopServer()
                    stopSelf()
                    return START_NOT_STICKY
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
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (isEnabled(this)) {
            start(this)
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun startServer() {
        promoteForeground()
        if (httpServer != null) return
        val rootDir = resolveRootDir(this)
        if (!rootDir.exists()) rootDir.mkdirs()
        httpServer = HttpFileServer(rootDir, AppConstants.HTTP_PORT).apply { startServer() }
    }

    private fun promoteForeground() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ip = getLocalIpAddress()
        val url = "http://$ip:${AppConstants.HTTP_PORT}/vod.json"
        try {
            enterForeground(buildNotification(url))
        } catch (t: Throwable) {
            android.util.Log.e("HttpServerService", "startForeground failed", t)
            try {
                enterForeground(buildFallbackNotification())
            } catch (t2: Throwable) {
                android.util.Log.e("HttpServerService", "startForeground fallback failed", t2)
            }
        }
    }

    private fun enterForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
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
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun buildFallbackNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "局域网源服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("XCCTV 源服务")
            .setContentText("局域网 HTTP 运行中")
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
