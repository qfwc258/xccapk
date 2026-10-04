package com.xcctv.tvassistant;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 开机 / 应用更新后自启核心服务（服务端）。
 * 是否拉起代理、是否自启源下载，由 CoreService 读取各自开关决定。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent intent) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(new Intent(c, CoreService.class));
            } else {
                c.startService(new Intent(c, CoreService.class));
            }
        } catch (Exception ignored) {
        }
    }
}
