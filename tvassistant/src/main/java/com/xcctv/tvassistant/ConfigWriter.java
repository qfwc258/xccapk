package com.xcctv.tvassistant;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URLDecoder;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 生成 mihomo 的 config.yaml。
 * 两种输入：
 *   1) 订阅地址(http/https) —— 用 proxy-providers 让内核自己去拉、自己定时更新；
 *   2) 整段 config.yaml    —— 直接使用，但把端口/局域网/日志这些顶层键换成我们的，避免重复键报错。
 */
public final class ConfigWriter {

    public static final int MIXED_PORT = 7890;
    public static final int CTRL_PORT = 9090;
    public static final int PANEL_PORT = 9091;
    public static final String DEFAULT_SECRET = "zakamihomo";

    /** 这些顶层键由本程序接管：原配置里出现的整块丢掉，换成我们生成的。 */
    private static final String[] MANAGED = {
            "mixed-port", "port", "socks-port", "redir-port", "tproxy-port",
            "allow-lan", "bind-address", "external-controller", "external-controller-tls",
            "external-controller-unix", "external-controller-pipe", "external-ui",
            "secret", "log-level", "log-file"
    };

    private ConfigWriter() {
    }

    public static File workDir(Context c) {
        File d = new File(c.getFilesDir(), "mihomo");
        if (!d.exists()) {
            d.mkdirs();
        }
        return d;
    }

    public static File configFile(Context c) {
        return new File(workDir(c), "config.yaml");
    }

    /** 仪表盘目录（external-ui 指向这里，相对内核工作目录即为 ./yacd） */
    public static File uiDir(Context c) {
        return new File(workDir(c), "yacd");
    }

    /** 首次运行把内置的 YACD 仪表盘（assets/yacd.zip）解压到 uiDir；已存在则跳过 */
    public static void ensureUi(Context c) {
        File dir = uiDir(c);
        if (new File(dir, "index.html").exists()) {
            return;
        }
        dir.mkdirs();
        try (ZipInputStream zis = new ZipInputStream(c.getAssets().open("yacd.zip"))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    continue;
                }
                File out = new File(dir, e.getName());
                File p = out.getParentFile();
                if (p != null) {
                    p.mkdirs();
                }
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = zis.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                    }
                }
                zis.closeEntry();
            }
            Log.i("TV助手", "yacd 仪表盘已解压到 " + dir);
        } catch (IOException ex) {
            Log.w("TV助手", "解压 yacd 失败: " + ex);
        }
    }

    /** 支持 clash://install-config?url=... 这种分享格式 */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.toLowerCase().startsWith("clash://")) {
            int i = s.toLowerCase().indexOf("url=");
            if (i >= 0) {
                String u = s.substring(i + 4);
                int amp = u.indexOf('&');
                if (amp >= 0) {
                    u = u.substring(0, amp);
                }
                try {
                    return URLDecoder.decode(u, "UTF-8").trim();
                } catch (Exception e) {
                    return u.trim();
                }
            }
        }
        return s;
    }

    public static boolean isUrl(String s) {
        String l = s.toLowerCase();
        return l.startsWith("http://") || l.startsWith("https://");
    }

    public static boolean looksLikeYaml(String s) {
        return s.contains("proxies:") || s.contains("proxy-groups:")
                || s.contains("proxy-providers:") || s.contains("proxies :");
    }

    /** 顶层公共设置：本机 + 局域网代理都靠这几行 */
    private static String head(boolean lan, String secret) {
        if (secret == null || secret.isEmpty()) {
            secret = DEFAULT_SECRET;
        }
        StringBuilder b = new StringBuilder();
        b.append("mixed-port: ").append(MIXED_PORT).append('\n');
        b.append("allow-lan: ").append(lan).append('\n');
        b.append("bind-address: '*'\n");
        b.append("log-level: info\n");
        b.append("external-controller: 0.0.0.0:").append(CTRL_PORT).append('\n');
        b.append("secret: ").append(secret).append('\n');
        b.append("external-ui: ./yacd\n");
        return b.toString();
    }

    /** 生成可直接写给内核的完整配置（旧接口，订阅模式走内核自拉） */
    public static String build(String input, boolean lan, String secret) {
        String s = normalize(input);
        if (isUrl(s)) {
            return subscriptionHttp(s, lan, secret);
        }
        return mergeWithYaml(s, lan, secret);
    }

    /**
     * 推荐入口：URL 先由 App 抓取并清洗非法 client-fingerprint，落盘为本地 provider；
     * 抓取失败但有本地缓存则继续用缓存；两者皆无才回退让内核自拉（旧模式）。
     * 返回 [完整配置, 给用户看的结果说明]。
     */
    public static String[] buildSmart(Context c, String input, boolean lan, String secret) {
        String s = normalize(input);
        if (isUrl(s)) {
            String fetched = SubFetcher.fetch(s);
            if (fetched != null) {
                String clean = SubFetcher.sanitize(fetched);
                String err = SubFetcher.writeProvider(c, clean);
                if (err == null) {
                    String note = SubFetcher.lastFixes > 0
                            ? "订阅已更新（修正 " + SubFetcher.lastFixes + " 处非法 client-fingerprint → chrome）"
                            : "订阅已更新（指纹正常，未做修改）";
                    return new String[]{subscriptionFile(lan, secret, s), note};
                }
                return new String[]{subscriptionHttp(s, lan, secret),
                        "⚠ 订阅缓存写入失败(" + err + ")，改由内核自拉"};
            }
            File pf = SubFetcher.providerFile(c);
            if (pf.exists() && pf.length() > 0) {
                return new String[]{subscriptionFile(lan, secret, s),
                        "⚠ 订阅在线下载失败，使用本地缓存的订阅"};
            }
            return new String[]{subscriptionHttp(s, lan, secret),
                    "⚠ 订阅下载失败且无本地缓存，改由内核自行拉取"};
        }
        return new String[]{mergeWithYaml(SubFetcher.sanitize(s), lan, secret),
                "已应用自定义 YAML" + (SubFetcher.lastFixes > 0
                        ? "（修正 " + SubFetcher.lastFixes + " 处非法 client-fingerprint）" : "")};
    }

    /** 订阅模式 · 首选：App 已把清洗后的订阅写到本地，内核直接读文件（健康检查仍生效） */
    private static String subscriptionFile(boolean lan, String secret, String url) {
        StringBuilder b = commonHead(lan, secret);
        b.append("proxy-providers:\n");
        b.append("  SUB:\n");
        b.append("    type: file\n");
        b.append("    path: ./providers/sub.yaml\n");
        b.append("    health-check:\n");
        b.append("      enable: true\n");
        b.append("      url: https://www.gstatic.com/generate_204\n");
        b.append("      interval: 300\n");
        commonTail(b);
        return b.toString();
    }

    /** 订阅模式 · 回退：内核自己去拉（无法修正订阅里的非法指纹） */
    private static String subscriptionHttp(String url, boolean lan, String secret) {
        StringBuilder b = commonHead(lan, secret);
        b.append("proxy-providers:\n");
        b.append("  SUB:\n");
        b.append("    type: http\n");
        b.append("    url: ").append(yq(url)).append('\n');
        b.append("    interval: 86400\n");
        b.append("    path: ./providers/sub.yaml\n");
        b.append("    header:\n");
        b.append("      User-Agent:\n");
        b.append("        - 'clash-verge/v1.6.2'\n");
        b.append("    health-check:\n");
        b.append("      enable: true\n");
        b.append("      url: https://www.gstatic.com/generate_204\n");
        b.append("      interval: 300\n");
        commonTail(b);
        return b.toString();
    }

    /** 订阅模式的公共头部（DNS/顶层优化参数） */
    private static StringBuilder commonHead(boolean lan, String secret) {
        StringBuilder b = new StringBuilder();
        b.append("# TV助手 - 订阅模式 - 自动生成，改这个文件没用，改了会被覆盖\n");
        b.append(head(lan, secret));
        b.append("mode: rule\n");
        b.append("ipv6: false\n");
        b.append("unified-delay: true\n");
        b.append("tcp-concurrent: true\n");
        b.append("find-process-mode: off\n");
        b.append("keep-alive-interval: 30\n");
        b.append("keep-alive-idle: 600\n");
        b.append("profile:\n");
        b.append("  store-selected: true\n");
        b.append("  store-fake-ip: true\n");
        b.append("dns:\n");
        b.append("  enable: true\n");
        b.append("  listen: 0.0.0.0:1053\n");
        b.append("  ipv6: false\n");
        b.append("  enhanced-mode: fake-ip\n");
        b.append("  fake-ip-range: 198.18.0.1/16\n");
        b.append("  fake-ip-filter:\n");
        b.append("    - '*.lan'\n");
        b.append("    - '*.local'\n");
        b.append("    - '+.local'\n");
        b.append("    - 'time.*.com'\n");
        b.append("    - 'ntp.*.com'\n");
        b.append("    - 'stun.*.*'\n");
        b.append("  default-nameserver:\n");
        b.append("    - 223.5.5.5\n");
        b.append("    - 119.29.29.29\n");
        b.append("  nameserver:\n");
        b.append("    - 223.5.5.5\n");
        b.append("    - 119.29.29.29\n");
        b.append("    - 'https://doh.pub/dns-query'\n");
        b.append("  fallback:\n");
        b.append("    - 'https://1.1.1.1/dns-query'\n");
        b.append("    - 'https://8.8.8.8/dns-query'\n");
        b.append("  fallback-filter:\n");
        b.append("    geoip: false\n");
        b.append("    ipcidr:\n");
        b.append("      - 240.0.0.0/4\n");
        return b;
    }

    /** 订阅模式的公共尾部（策略组 + 规则）。双层组：自动选择(url-test) + PROXY(select，默认自动) */
    private static void commonTail(StringBuilder b) {
        b.append("proxy-groups:\n");
        b.append("  - name: 自动选择\n");
        b.append("    type: url-test\n");
        b.append("    use:\n");
        b.append("      - SUB\n");
        b.append("    url: https://www.gstatic.com/generate_204\n");
        b.append("    interval: 120\n");
        b.append("    tolerance: 50\n");
        b.append("    lazy: false\n");
        b.append("  - name: PROXY\n");
        b.append("    type: select\n");
        b.append("    proxies:\n");
        b.append("      - 自动选择\n");
        b.append("    use:\n");
        b.append("      - SUB\n");
        b.append("rules:\n");
        b.append("  - IP-CIDR,127.0.0.0/8,DIRECT,no-resolve\n");
        b.append("  - IP-CIDR,10.0.0.0/8,DIRECT,no-resolve\n");
        b.append("  - IP-CIDR,172.16.0.0/12,DIRECT,no-resolve\n");
        b.append("  - IP-CIDR,192.168.0.0/16,DIRECT,no-resolve\n");
        b.append("  - MATCH,PROXY\n");
    }

    /**
     * 用户直接贴了完整 config.yaml：保留他的 proxies / proxy-groups / rules / dns，
     * 只把被接管的顶层键（端口、allow-lan 等）摘掉，换成我们的，避免 YAML 重复键。
     */
    private static String mergeWithYaml(String yaml, boolean lan, String secret) {
        StringBuilder out = new StringBuilder();
        out.append("# TV助手 - 自定义配置模式\n");
        out.append(head(lan, secret));
        String[] lines = yaml.split("\n", -1);
        boolean skipping = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.length() == 0) {
                continue;
            }
            char c0 = line.charAt(0);
            if (c0 != ' ' && c0 != '\t' && c0 != '-') {
                if (c0 == '#') {
                    if (!skipping) {
                        out.append(line).append('\n');
                    }
                    continue;
                }
                int colon = line.indexOf(':');
                String key = colon > 0 ? line.substring(0, colon).trim() : null;
                if (key != null && isManaged(key)) {
                    skipping = true;
                    continue;
                }
                skipping = false;
            }
            if (!skipping) {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    private static boolean isManaged(String key) {
        for (int i = 0; i < MANAGED.length; i++) {
            if (MANAGED[i].equals(key)) {
                return true;
            }
        }
        return false;
    }

    /** YAML 单引号字符串 */
    private static String yq(String v) {
        return "'" + v.replace("'", "''") + "'";
    }

    /** 写盘。返回 null 表示成功，否则返回错误信息。 */
    public static String write(Context c, String content) {
        File f = configFile(c);
        Writer w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
            w.write(content);
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
