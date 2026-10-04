package com.xcctv.tvassistant;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 订阅抓取与清洗。
 * 修复「wrong clientFingerprint:unsafe」：部分机场订阅带 client-fingerprint: unsafe，
 * 这不是 mihomo 认可的 uTLS 指纹（合法值 chrome/firefox/safari/ios/android/edge/360/qq/random 等），
 * 会导致 TLS 节点握手失败、代理不通。这里统一替换为 chrome。
 * 抓取成功后写入本地 provider 文件，内核改用 type: file，不再自己去拉（也就无法被污染）。
 */
public final class SubFetcher {

    /** 最近一次 sanitize 替换处数 */
    public static int lastFixes = 0;

    private static final Pattern FP = Pattern.compile(
            "(?i)((?:global-)?client-fingerprint\\s*:\\s*)['\"]?([A-Za-z0-9_-]+)['\"]?");
    private static final Pattern FP_LINK = Pattern.compile("(?i)(fp=)(unsafe)([&\\s'\"&P])");

    /** mihomo 认可的 uTLS 指纹 */
    private static final Set<String> OK = new HashSet<String>(Arrays.asList(
            "chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq",
            "random", "randomized", "chrome*", ""));

    private SubFetcher() {
    }

    /** 把非法的 client-fingerprint / fp= 值替换为 chrome。返回清洗后的文本。 */
    public static String sanitize(String yaml) {
        if (yaml == null || yaml.isEmpty()) {
            return yaml;
        }
        lastFixes = 0;
        StringBuffer sb = new StringBuffer();
        Matcher m = FP.matcher(yaml);
        while (m.find()) {
            String val = m.group(2).toLowerCase();
            if (!OK.contains(val)) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + "chrome"));
                lastFixes++;
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(sb);
        String out = sb.toString();

        // 分享链接形式：...&fp=unsafe&...
        StringBuffer sb2 = new StringBuffer();
        Matcher m2 = FP_LINK.matcher(out);
        while (m2.find()) {
            m2.appendReplacement(sb2, Matcher.quoteReplacement(m2.group(1) + "chrome" + m2.group(3)));
            lastFixes++;
        }
        m2.appendTail(sb2);
        return sb2.toString();
    }

    /** 直接抓取订阅（跟随 3xx 跳转，最多 5 次）。失败返回 null。 */
    public static String fetch(String url) {
        String cur = url;
        for (int hop = 0; hop < 5 && cur != null; hop++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(cur).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(12000);
                conn.setInstanceFollowRedirects(false);
                conn.setRequestProperty("User-Agent", "clash-verge/v1.6.2");
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = conn.getHeaderField("Location");
                    conn.disconnect();
                    cur = loc == null ? null : loc.trim();
                    continue;
                }
                if (code != 200) {
                    return null;
                }
                InputStream in = conn.getInputStream();
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                StringBuilder b = new StringBuilder();
                char[] buf = new char[8192];
                int n;
                while ((n = r.read(buf)) > 0 && b.length() < 4 * 1024 * 1024) {
                    b.append(buf, 0, n);
                }
                r.close();
                return b.toString();
            } catch (Exception e) {
                return null;
            } finally {
                if (conn != null) {
                    try {
                        conn.disconnect();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return null;
    }

    /** 本地 provider 文件（内核 config 里 path: ./providers/sub.yaml） */
    public static File providerFile(Context c) {
        File d = new File(ConfigWriter.workDir(c), "providers");
        if (!d.exists()) {
            d.mkdirs();
        }
        return new File(d, "sub.yaml");
    }

    /** 清洗并写入 provider 文件。返回 null=成功，否则错误信息。 */
    public static String writeProvider(Context c, String yaml) {
        File f = providerFile(c);
        Writer w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8);
            w.write(yaml);
            w.flush();
            return null;
        } catch (Exception e) {
            return String.valueOf(e.getMessage());
        } finally {
            try {
                if (w != null) {
                    w.close();
                }
            } catch (Exception ignored) {
            }
        }
    }
}
