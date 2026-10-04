package com.zaka.mihomotv;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    public static final String PREF = "cfg";
    public static final String K_SUB = "sub";
    public static final String K_LAN = "lan";
    public static final String K_BOOT = "boot";

    private EditText etSub;
    private CheckBox cbLan;
    private CheckBox cbBoot;
    private TextView tvStatus;

    private SharedPreferences sp;
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
        sp = getSharedPreferences(PREF, MODE_PRIVATE);

        etSub = (EditText) findViewById(R.id.etSub);
        cbLan = (CheckBox) findViewById(R.id.cbLan);
        cbBoot = (CheckBox) findViewById(R.id.cbBoot);
        tvStatus = (TextView) findViewById(R.id.tvStatus);
        Button btnStart = (Button) findViewById(R.id.btnStart);
        Button btnStop = (Button) findViewById(R.id.btnStop);

        etSub.setText(sp.getString(K_SUB, ""));
        cbLan.setChecked(sp.getBoolean(K_LAN, true));
        cbBoot.setChecked(sp.getBoolean(K_BOOT, true));

        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                apply(true);
            }
        });
        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(MainActivity.this, MihomoService.class);
                i.setAction(MihomoService.ACTION_STOP);
                startService(i);
                toast("已停止");
            }
        });
        cbLan.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                sp.edit().putBoolean(K_LAN, checked).apply();
                if (MihomoService.isRunning()) {
                    apply(false);
                }
            }
        });
        cbBoot.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                sp.edit().putBoolean(K_BOOT, checked).apply();
            }
        });

        btnStart.requestFocus();
        askNotification();

        // 打开即启动：有存过的配置就直接拉起来
        if (sp.getString(K_SUB, "").length() > 0 && ConfigWriter.configFile(this).exists()) {
            apply(false);
        }
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

    /** 保存 + （重新）启动核核心 */
    private void apply(boolean loud) {
        String input = ConfigWriter.normalize(etSub.getText().toString());
        if (input.length() == 0) {
            if (loud) {
                toast("先填订阅地址，或整段 config.yaml 贴进来");
            }
            return;
        }
        if (!ConfigWriter.isUrl(input) && !ConfigWriter.looksLikeYaml(input)) {
            if (loud) {
                toast("认不出来：要么 http(s) 订阅地址，要么带 proxies: 的完整 YAML");
            }
            return;
        }
        String yaml = ConfigWriter.build(input, cbLan.isChecked());
        String err = ConfigWriter.write(this, yaml);
        if (err != null) {
            if (loud) {
                toast("配置写不进去: " + err);
            }
            return;
        }
        sp.edit()
                .putString(K_SUB, input)
                .putBoolean(K_LAN, cbLan.isChecked())
                .apply();

        Intent i = new Intent(this, MihomoService.class);
        i.setAction(MihomoService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        if (loud) {
            toast("已启动，本机 127.0.0.1:" + ConfigWriter.MIXED_PORT);
        }
    }

    private void render() {
        boolean alive = MihomoService.isRunning();
        boolean port = Net.portOpen("127.0.0.1", ConfigWriter.MIXED_PORT);
        String ip = Net.lanIp();

        StringBuilder b = new StringBuilder();
        if (!alive) {
            b.append("状态: 已停止");
        } else if (port) {
            b.append("状态: 运行中");
        } else {
            b.append("状态: 启动中…");
        }
        b.append("      内核 ").append(MihomoService.CORE_VERSION).append('\n');
        b.append("本机   127.0.0.1:").append(ConfigWriter.MIXED_PORT).append('\n');
        if (cbLan.isChecked()) {
            b.append("局域网 ").append(ip).append(':').append(ConfigWriter.MIXED_PORT).append('\n');
        } else {
            b.append("局域网 已关闭\n");
        }
        b.append("面板   http://").append(ip).append(':').append(ConfigWriter.CTRL_PORT)
                .append("  密钥 ").append(ConfigWriter.CTRL_SECRET).append('\n');
        b.append("──────────── 日志 ────────────\n");
        String tail = MihomoService.tail(7);
        b.append(tail.length() == 0 ? "(空)\n" : tail);
        tvStatus.setText(b.toString());
    }

    private void askNotification() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
            }
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
