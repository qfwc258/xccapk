package com.zaka.mihomotv;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URLDecoder;

/**
 * 生成 mihomo 的 config.yaml。
 * 两种输入：
 *   1) 订阅地址(http/https) —— 用 proxy-providers 让内核自己去拉、自己定时更新；
 *   2) 整段 config.yaml    —— 直接使用，但把端口/局域网/日志这些顶层键换成我们的，避免重复键报错。
 */
public final class ConfigWriter {

    public static final int MIXED_PORT = 7890;
    public static final int CTRL_PORT = 9090;
    public static final String CTRL_SECRET = "zakamihomo";

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
    private static String head(boolean lan) {
        StringBuilder b = new StringBuilder();
        b.append("mixed-port: ").append(MIXED_PORT).append('\n');
        b.append("allow-lan: ").append(lan).append('\n');
        b.append("bind-address: '*'\n");
        b.append("log-level: info\n");
        b.append("external-controller: 0.0.0.0:").append(CTRL_PORT).append('\n');
        b.append("secret: ").append(CTRL_SECRET).append('\n');
        return b.toString();
    }

    /** 生成可直接写给内核的完整配置 */
    public static String build(String input, boolean lan) {
        String s = normalize(input);
        if (isUrl(s)) {
            return subscription(s, lan);
        }
        return mergeWithYaml(s, lan);
    }

    private static String subscription(String url, boolean lan) {
        StringBuilder b = new StringBuilder();
        b.append("# Mihomo TV - 订阅模式 - 自动生成，改这个文件没用，改了会被覆盖\n");
        b.append(head(lan));
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
        b.append("proxy-groups:\n");
        b.append("  - name: PROXY\n");
        b.append("    type: select\n");
        b.append("    use:\n");
        b.append("      - SUB\n");
        b.append("rules:\n");
        b.append("  - IP-CIDR,127.0.0.0/8,DIRECT,no-resolve\n");
        b.append("  - IP-CIDR,10.0.0.0/8,DIRECT,no-resolve\n");
        b.append("  - IP-CIDR,172.16.0.0/12,DIRECT,no-resolve\n");
        b.append("  - IP-CIDR,192.168.0.0/16,DIRECT,no-resolve\n");
        b.append("  - MATCH,PROXY\n");
        return b.toString();
    }

    /**
     * 用户直接贴了完整 config.yaml：保留他的 proxies / proxy-groups / rules / dns，
     * 只把被接管的顶层键（端口、allow-lan 等）摘掉，换成我们的，避免 YAML 重复键。
     */
    private static String mergeWithYaml(String yaml, boolean lan) {
        StringBuilder out = new StringBuilder();
        out.append("# Mihomo TV - 自定义配置模式\n");
        out.append(head(lan));
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
