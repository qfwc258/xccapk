package com.xcctv.tvassistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
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
 * 核心前台服务 —— TV助手作为「服务端」的载体：
 *   1) 拉起内嵌 HTTP 网页控制端（WebServer，:9091），手机浏览器访问即可设置/控制；
 *   2) 管理 mihomo 代理内核（libmihomo.so）的启停与配置热重启；
 *   3) 源下载由 Kotlin 侧 SourceController 承担，本服务只负责存活与保活。
 * 服务常驻前台，从最近任务划掉也继续跑。
 */
public class CoreService extends Service implements WebServer.Control {

    public static final String ACTION_PROXY_START = "com.xcctv.tvassistant.action.PROXY_START";
    public static final String ACTION_PROXY_STOP = "com.xcctv.tvassistant.action.PROXY_STOP";
    public static final String CORE_VERSION = "v1.19.32";

    private static final int NID = 0x5A4B;
    private static final String CHANNEL = "tvassistant_core";
    private static final int LOG_MAX = 200;

    private static final Object LOCK = new Object();
    private static final ArrayDeque<String> LOG = new ArrayDeque<String>();
    private static volatile boolean running = false;
    private static volatile int pid = 0;

    private Process proc;
    private PowerManager.WakeLock wake;
    private Thread reader;
    private Thread waiter;
    private WebServer web;

    public static boolean isProxyAlive() {
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
        ConfigWriter.ensureUi(this);
        web = new WebServer(this, this);
        web.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundCompat();

        String action = intent == null ? null : intent.getAction();
        if (ACTION_PROXY_STOP.equals(action)) {
            stopProxy();
        } else if (ACTION_PROXY_START.equals(action)) {
            startProxy();
        } else {
            // 默认：按开机设置决定是否拉起代理；网页端可随时改
            SharedPreferences sp = Prefs.get(this);
            String sub = sp.getString(Prefs.K_SUB, "");
            if (sp.getBoolean(Prefs.K_BOOT_PROXY, true) && sub.length() > 0
                    && ConfigWriter.configFile(this).exists()) {
                startProxy();
            }
            // 开机自启源下载（若开启且已配置源地址）
            if (sp.getBoolean(Prefs.K_BOOT_SRC, false)
                    && sp.getString(Prefs.K_SRC_URL, "").length() > 0) {
                SourceController.INSTANCE.start(this, sp.getString(Prefs.K_SRC_URL, ""));
            }
        }
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 故意什么都不做：从最近任务里划掉也继续跑
    }

    @Override
    public void onDestroy() {
        if (web != null) {
            web.stop();
        }
        stopProxy();
        super.onDestroy();
    }

    // -------------------------------------------------------- WebServer.Control 实现

    @Override
    public void applyProxy(String sub, String secret, boolean lan) {
        sub = ConfigWriter.normalize(sub);
        if (secret == null || secret.trim().isEmpty()) {
            secret = ConfigWriter.DEFAULT_SECRET;
        }
        if (sub.isEmpty()) {
            return;
        }
        if (!ConfigWriter.isUrl(sub) && !ConfigWriter.looksLikeYaml(sub)) {
            return;
        }
        String yaml = ConfigWriter.build(sub, lan, secret);
        String err = ConfigWriter.write(this, yaml);
        if (err != null) {
            log("配置写入失败: " + err);
            return;
        }
        Prefs.get(this).edit()
                .putString(Prefs.K_SUB, sub)
                .putString(Prefs.K_SECRET, secret)
                .putBoolean(Prefs.K_LAN, lan)
                .apply();
        startProxy();
    }

    @Override
    public void startProxy() {
        if (proc != null) {
            stopProxy();
        }
        startCore();
    }

    @Override
    public void stopProxy() {
        stopCore();
    }

    @Override
    public boolean isProxyRunning() {
        return running;
    }

    // ---------------------------------------------------------------- 内核

    private void startCore() {
        if (proc != null) {
            return;
        }
        ConfigWriter.ensureUi(this);
        File cfg = ConfigWriter.configFile(this);
        if (!cfg.exists() || cfg.length() == 0) {
            log("没有配置文件，先在网页端填订阅并保存");
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
                    BufferedReader r = new BufferedReader(
                            new InputStreamReader(p.getInputStream(), "UTF-8"));
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
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvassistant:core");
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
        NotificationChannel ch = new NotificationChannel(CHANNEL, "TV助手服务",
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
                new Intent(this, CoreService.class).setAction(ACTION_PROXY_STOP), piFlag);

        String ip = Net.lanIp();
        String title = running ? "TV助手 · 代理运行中" : "TV助手 · 控制端已就绪";
        String text = "网页控制 http://" + ip + ":" + ConfigWriter.PANEL_PORT
                + (running ? " · 代理 " + ip + ":" + ConfigWriter.MIXED_PORT : "");

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
                .setContentIntent(content);
        if (running) {
            b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止代理", stop);
        }
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
}
