package com.xcctv.tvassistant;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.Settings;
import android.widget.TextView;

/**
 * 设备端极简界面：把「手机网页控制地址」亮出来，并显示两端状态。
 * 所有设置与控制都在手机浏览器完成（打开 http://本机IP:9091/）。
 * 打开即启动 CoreService（服务端），使其常驻；并主动申请存储权限（源下载需要）。
 */
public class MainActivity extends Activity {

    private static final int REQ_STORAGE = 10;

    private TextView tvInfo;
    private final Handler handler = new Handler();
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        tvInfo = findViewById(R.id.tv_info);
        askNotification();
        askStorage();
        // 打开即启动服务端
        startService(new Intent(this, CoreService.class));
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    private void render() {
        String ip = Net.lanIp();
        boolean proxy = CoreService.isProxyAlive();
        boolean proxyPort = Net.portOpen("127.0.0.1", ConfigWriter.MIXED_PORT);
        String src = SourceController.INSTANCE.statusText();

        StringBuilder b = new StringBuilder();
        b.append("TV助手 · 服务端\n\n");
        b.append("控制端（手机浏览器打开）：\n");
        b.append("  http://").append(ip).append(':').append(ConfigWriter.PANEL_PORT).append('\n');
        b.append("  ── 代理设置 / 源下载 / 仪表盘都在这里 ──\n\n");
        b.append("代理内核：").append(proxy ? (proxyPort ? "运行中" : "启动中…") : "已停止").append('\n');
        if (proxy) {
            b.append("  本机  ").append(ip).append(':').append(ConfigWriter.MIXED_PORT).append('\n');
        }
        if (!proxy && CoreService.lastError().length() > 0) {
            b.append("  ✗ ").append(CoreService.lastError()).append('\n');
        }
        b.append("源下载：").append(src).append('\n');
        if (!storageGranted()) {
            b.append("⚠ 存储权限未授予，源下载不可用（本界面已自动弹授权）\n");
        }
        b.append("\n内核 ").append(CoreService.CORE_VERSION);
        tvInfo.setText(b.toString());
    }

    // -------------------------------------------------------- 权限

    private boolean storageGranted() {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        if (Build.VERSION.SDK_INT >= 23) {
            return checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE")
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    /** 主动申请存储权限：11+ 引导到「所有文件访问」开关页，6~10 直接弹运行时授权 */
    private void askStorage() {
        if (storageGranted()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (Exception ignored) {
                }
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{
                    "android.permission.READ_EXTERNAL_STORAGE",
                    "android.permission.WRITE_EXTERNAL_STORAGE"}, REQ_STORAGE);
        }
    }

    private void askNotification() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
            }
        }
    }
}
