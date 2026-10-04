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
    private static volatile String lastError = "";
    /** true=正在主动停止（网页/通知触发），false=内核自己退了（视为异常） */
    private static volatile boolean stopping = false;

    private volatile Process proc;
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

    /** 最近一次内核异常退出的原因（给网页端与设备端 UI 显示） */
    public static String lastError() {
        return lastError;
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
        final String fSub = sub;
        final String fSecret = secret;
        final boolean fLan = lan;
        // buildSmart 含网络请求，放后台线程
        new Thread(new Runnable() {
            @Override
            public void run() {
                String[] r = ConfigWriter.buildSmart(CoreService.this, fSub, fLan, fSecret);
                String err = ConfigWriter.write(CoreService.this, r[0]);
                if (err != null) {
                    log("配置写入失败: " + err);
                    return;
                }
                log(r[1]);
                Prefs.get(CoreService.this).edit()
                        .putString(Prefs.K_SUB, fSub)
                        .putString(Prefs.K_SECRET, fSecret)
                        .putBoolean(Prefs.K_LAN, fLan)
                        .apply();
                startProxy();
            }
        }, "apply-proxy").start();
    }

    @Override
    public void startProxy() {
        final Process old = proc;
        // 整个启停串行放后台线程：避免主线程网络 I/O，且给旧进程留出释放端口的时间
        new Thread(new Runnable() {
            @Override
            public void run() {
                synchronized (START_LOCK) {
                    if (old != null) {
                        stopCore();
                        try {
                            Thread.sleep(1200);
                        } catch (Exception ignored) {
                        }
                    }
                    startCoreSync();
                }
            }
        }, "core-starter").start();
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

    private static final Object START_LOCK = new Object();

    /**
     * 启动内核（在 core-starter 线程调用）。
     * 每次启动前按当前偏好重新生成配置——订阅模式下即完成一次订阅刷新
     * （App 抓取→清洗非法指纹→落盘本地 provider，详见 ConfigWriter.buildSmart）。
     */
    private void startCoreSync() {
        if (proc != null) {
            return;
        }
        ConfigWriter.ensureUi(this);
        SharedPreferences sp = Prefs.get(this);
        String sub = sp.getString(Prefs.K_SUB, "");
        File cfg = ConfigWriter.configFile(this);
        if (sub.length() > 0) {
            String[] r = ConfigWriter.buildSmart(this, sub,
                    sp.getBoolean(Prefs.K_LAN, true),
                    sp.getString(Prefs.K_SECRET, ConfigWriter.DEFAULT_SECRET));
            String err = ConfigWriter.write(this, r[0]);
            log(r[1] + (err != null ? "（写入失败: " + err + "）" : ""));
        }
        if (!cfg.exists() || cfg.length() == 0) {
            lastError = "还没有配置：先在网页端「代理」页填订阅并保存";
            log(lastError);
            return;
        }
        String bin = getApplicationInfo().nativeLibraryDir + "/libmihomo.so";
        File b = new File(bin);
        if (!b.exists()) {
            lastError = "内核文件缺失: " + bin;
            log(lastError);
            return;
        }
        b.setExecutable(true, false);

        // 端口预检：被其它进程（比如旧版 mihomotv 还在跑）占用时，内核会起不来
        if (Net.portOpen("127.0.0.1", ConfigWriter.MIXED_PORT)) {
            log("⚠ 端口 " + ConfigWriter.MIXED_PORT + " 已被占用——若装过旧版 mihomotv 请先停用/卸载它");
        }

        File wd = ConfigWriter.workDir(this);
        clearLog();
        lastError = "";
        stopping = false;
        log("启动内核 " + CORE_VERSION);
        try {
            List<String> cmd = new ArrayList<String>();
            cmd.add(bin);
            cmd.add("-d");
            cmd.add(wd.getAbsolutePath());
            cmd.add("-f");
            cmd.add(cfg.getAbsolutePath());
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(wd);
            pb.redirectErrorStream(true);
            pb.environment().put("HOME", wd.getAbsolutePath());
            pb.environment().put("TMPDIR", getCacheDir().getAbsolutePath());
            proc = pb.start();
            running = true;
        } catch (Exception e) {
            lastError = "无法启动内核进程: " + e.getMessage()
                    + "（Android 10+ 禁止从数据目录执行程序时会出现）";
            log(lastError);
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
                proc = null;
                if (stopping) {
                    log("内核已停止");
                } else {
                    String tail = tail(6).replace('\n', ' ').trim();
                    String hint = diagnose(String.valueOf(code), tail);
                    lastError = "内核异常退出 code=" + code + (hint.isEmpty() ? "" : "：" + hint);
                    log("✗ " + lastError);
                    log("最近输出: " + tail);
                    refreshNotification();
                }
            }
        }, "mihomo-wait");
        waiter.setDaemon(true);
        waiter.start();

        // 启动自检：2.5 秒后还活着，再探测 external-controller 是否应答
        Thread probe = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(2500);
                } catch (Exception ignored) {
                }
                if (!running || p != proc) {
                    return;
                }
                if (!p.isAlive()) {
                    return;
                }
                String secret = Prefs.get(CoreService.this)
                        .getString(Prefs.K_SECRET, ConfigWriter.DEFAULT_SECRET);
                String ver = ctrlVersion(secret);
                if (ver != null) {
                    log("✓ 内核就绪，控制接口应答 " + ver);
                } else {
                    log("内核进程存活，但 " + ConfigWriter.CTRL_PORT + " 端口尚未应答（可能在拉取订阅）");
                }
                refreshNotification();
            }
        }, "mihomo-probe");
        probe.setDaemon(true);
        probe.start();

        acquireWake();
        refreshNotification();
    }

    /** 探测 external-controller /version，返回 version 字符串或 null */
    private String ctrlVersion(String secret) {
        try {
            java.net.Socket s = new java.net.Socket();
            s.connect(new java.net.InetSocketAddress("127.0.0.1", ConfigWriter.CTRL_PORT), 800);
            java.io.OutputStream os = s.getOutputStream();
            String req = "GET /version HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                    + "Authorization: Bearer " + (secret == null ? "" : secret) + "\r\n"
                    + "Connection: close\r\n\r\n";
            os.write(req.getBytes("UTF-8"));
            os.flush();
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null && sb.length() < 600) {
                sb.append(l).append('\n');
            }
            s.close();
            String body = sb.toString();
            int i = body.indexOf("version");
            if (i >= 0) {
                return "v" + body.replaceAll(".*\"version\"\\s*:\\s*\"([^\"]+)\".*", "$1");
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 根据退出码与最后输出猜测可读的原因 */
    private static String diagnose(String code, String tail) {
        String t = tail.toLowerCase();
        if (t.contains("address already in use") || t.contains("bind") && t.contains("use")) {
            return "端口被占用——旧版 mihomotv 或其它代理正在运行，请先停止它";
        }
        if (t.contains("permission denied")) {
            return "系统拒绝执行内核（W^X 限制）";
        }
        if (t.contains("no such file") || t.contains("not found")) {
            return "内核或配置文件路径不存在";
        }
        if (t.contains("yaml") || t.contains("parse error") || t.contains("unmarshal")
                || t.contains("invalid")) {
            return "配置解析失败——检查订阅/YAML 内容";
        }
        if ("1".equals(code) && tail.length() > 0) {
            return "详见下方最近输出";
        }
        return "";
    }

    private void stopCore() {
        stopping = true;
        lastError = "";
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
