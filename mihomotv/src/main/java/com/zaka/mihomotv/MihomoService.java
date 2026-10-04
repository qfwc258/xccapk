package com.zaka.mihomotv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 前台服务，跑 mihomo 内核。
 * 内核二进制打包在 jniLibs 里，安装时会被解到 nativeLibraryDir，Android 只允许从那儿执行。
 */
public class MihomoService extends Service {

    public static final String ACTION_START = "com.zaka.mihomotv.action.START";
    public static final String ACTION_STOP = "com.zaka.mihomotv.action.STOP";
    public static final String CORE_VERSION = "v1.19.32";

    private static final int NID = 0x5A4B;
    private static final String CHANNEL = "mihomo_core";
    private static final int LOG_MAX = 200;

    private static final Object LOCK = new Object();
    private static final ArrayDeque<String> LOG = new ArrayDeque<String>();
    private static volatile boolean running = false;
    private static volatile int pid = 0;

    private Process proc;
    private PowerManager.WakeLock wake;
    private Thread reader;
    private Thread waiter;
    private PanelServer panel;

    public static boolean isRunning() {
        return running;
    }

    public static int getPid() {
        return pid;
    }

    public static void clearLog() {
        synchronized (LOCK) {
            LOG.clear();
        }
    }

    public static String tail(int n) {
        StringBuilder b = new StringBuilder();
        synchronized (LOCK) {
            int skip = LOG.size() - n;
            int i = 0;
            for (String s : LOG) {
                if (i++ < skip) {
                    continue;
                }
                b.append(s).append('\n');
            }
        }
        return b.toString();
    }

    private static void log(String s) {
        String line = s == null ? "" : s.trim();
        if (line.length() == 0) {
            return;
        }
        if (line.length() > 160) {
            line = line.substring(0, 160) + "…";
        }
        synchronized (LOCK) {
            LOG.addLast(line);
            while (LOG.size() > LOG_MAX) {
                LOG.removeFirst();
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        // 管理面板随服务存活（默认开即启动，面板始终可达）
        panel = new PanelServer(this);
        panel.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopCore();
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }
        startForegroundCompat();
        if (proc != null) {
            stopCore();   // 重启：配置可能已经改了
        }
        startCore();
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 故意什么都不做：从最近任务里划掉也继续跑
    }

    @Override
    public void onDestroy() {
        if (panel != null) {
            panel.stop();
        }
        stopCore();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 内核

    private void startCore() {
        if (proc != null) {
            return;
        }
        ConfigWriter.ensureUi(this);
        File cfg = ConfigWriter.configFile(this);
        if (!cfg.exists() || cfg.length() == 0) {
            log("没有配置文件，先在界面上填订阅并保存");
            return;
        }
        String bin = getApplicationInfo().nativeLibraryDir + "/libmihomo.so";
        File b = new File(bin);
        if (!b.exists()) {
            log("内核文件缺失: " + bin);
            return;
        }
        b.setExecutable(true, false);

        File wd = ConfigWriter.workDir(this);
        clearLog();
        log("启动内核 " + CORE_VERSION);
        try {
            List<String> cmd = new ArrayList<String>();
            cmd.add(bin);
            cmd.add("-d");
            cmd.add(wd.getAbsolutePath());
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(wd);
            pb.redirectErrorStream(true);
            pb.environment().put("HOME", wd.getAbsolutePath());
            pb.environment().put("TMPDIR", getCacheDir().getAbsolutePath());
            proc = pb.start();
            running = true;
        } catch (Exception e) {
            log("启动失败: " + e.getMessage());
            proc = null;
            running = false;
            return;
        }

        final Process p = proc;
        try {
            java.lang.reflect.Field f = Process.class.getDeclaredField("pid");
            f.setAccessible(true);
            pid = ((Integer) f.get(p)).intValue();
        } catch (Throwable ignored) {
            pid = 0;
        }

        reader = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
                    String l;
                    while ((l = r.readLine()) != null) {
                        log(l);
                    }
                } catch (Exception ignored) {
                }
            }
        }, "mihomo-out");
        reader.setDaemon(true);
        reader.start();

        waiter = new Thread(new Runnable() {
            @Override
            public void run() {
                int code;
                try {
                    code = p.waitFor();
                } catch (Exception e) {
                    code = -1;
                }
                running = false;
                pid = 0;
                log("内核已退出 (code=" + code + ")");
            }
        }, "mihomo-wait");
        waiter.setDaemon(true);
        waiter.start();

        acquireWake();
        refreshNotification();
    }

    private void stopCore() {
        Process p = proc;
        proc = null;
        running = false;
        pid = 0;
        if (p != null) {
            try {
                p.destroy();
                final Process fp = p;
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Thread.sleep(1500);
                            fp.destroyForcibly();
                        } catch (Exception ignored) {
                        }
                    }
                });
                t.setDaemon(true);
                t.start();
            } catch (Exception ignored) {
            }
        }
        releaseWake();
        log("已停止");
    }

    private void acquireWake() {
        try {
            if (wake != null && wake.isHeld()) {
                return;
            }
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mihomo:core");
            wake.setReferenceCounted(false);
            wake.acquire();
        } catch (Exception ignored) {
        }
    }

    private void releaseWake() {
        try {
            if (wake != null && wake.isHeld()) {
                wake.release();
            }
        } catch (Exception ignored) {
        }
        wake = null;
    }

    // ------------------------------------------------------------ 通知栏

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Mihomo 代理",
                NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ch.setSound(null, null);
        ch.enableVibration(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int piFlag = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            piFlag |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent content = PendingIntent.getActivity(this, 1, open, piFlag);
        PendingIntent stop = PendingIntent.getService(this, 2,
                new Intent(this, MihomoService.class).setAction(ACTION_STOP), piFlag);

        String ip = Net.lanIp();
        String title = running ? "Mihomo 代理运行中" : "Mihomo 面板已就绪";
        String text = running
                ? "本机 " + ip + ":" + ConfigWriter.MIXED_PORT + " · 局域网同地址"
                : "管理面板 http://" + ip + ":" + ConfigWriter.PANEL_PORT;

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(content)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stop);
        if (Build.VERSION.SDK_INT >= 16) {
            b.setPriority(Notification.PRIORITY_LOW);
        }
        return b.build();
    }

    private void startForegroundCompat() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NID, n);
        }
    }

    private void refreshNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NID, buildNotification());
        } catch (Exception ignored) {
        }
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(true);
        } else {
            stopForeground(true);
        }
    }
}
