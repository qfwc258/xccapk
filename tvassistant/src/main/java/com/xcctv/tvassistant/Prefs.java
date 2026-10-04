package com.xcctv.tvassistant;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 两功能共用的 SharedPreferences 键。
 * 代理侧与源下载侧都从这里读/写，设备端 UI、网页控制端、CoreService 共用同一份状态。
 */
public final class Prefs {

    public static final String NAME = "tvassistant";
    public static final String K_SUB = "sub";            // 代理订阅地址 / 自定义 YAML
    public static final String K_SECRET = "secret";      // 控制面板密钥（可改）
    public static final String K_LAN = "lan";            // 允许局域网代理
    public static final String K_BOOT_PROXY = "boot_proxy"; // 开机自启代理
    public static final String K_SRC_URL = "src_url";    // xcctv 源地址
    public static final String K_BOOT_SRC = "boot_src";  // 开机自启源下载

    public static final String K_PASS = "panel_pass";    // 网页控制端访问密码（空=免密）
    public static final String K_SUBS = "subs_json";     // 订阅列表 [{"n":名称,"u":地址}]
    public static final String K_SUB_CUR = "sub_cur";    // 当前使用的订阅地址

    private Prefs() {
    }

    public static SharedPreferences get(Context c) {
        return c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }
}
