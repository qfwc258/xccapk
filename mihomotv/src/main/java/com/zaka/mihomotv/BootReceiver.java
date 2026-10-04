package com.zaka.mihomotv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent intent) {
        SharedPreferences sp = c.getSharedPreferences(MainActivity.PREF, Context.MODE_PRIVATE);
        if (!sp.getBoolean(MainActivity.K_BOOT, true)) {
            return;
        }
        if (sp.getString(MainActivity.K_SUB, "").length() == 0) {
            return;
        }
        if (!ConfigWriter.configFile(c).exists()) {
            return;
        }
        Intent s = new Intent(c, MihomoService.class).setAction(MihomoService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(s);
            } else {
                c.startService(s);
            }
        } catch (Exception ignored) {
        }
    }
}
