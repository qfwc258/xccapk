package com.xcctv.tvassistant;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.Collections;
import java.util.Enumeration;

public final class Net {

    /** 内核 API 调用失败日志钩子（CoreService 启动时挂接，避免静默失败） */
    public static interface Logger {
        void log(String s);
    }

    private static volatile Logger logger;

    public static void setLogger(Logger l) {
        logger = l;
    }

    private static void logFail(String what, Exception e) {
        Logger l = logger;
        if (l != null) {
            l.log("⚠ 内核API " + what + " 失败: " + e);
        }
    }

    private Net() {
    }

    /** 取本机在局域网里的 IPv4，取不到返回 127.0.0.1 */
    public static String lanIp() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface ni : Collections.list(nis)) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
                    continue;
                }
                String name = ni.getName() == null ? "" : ni.getName().toLowerCase();
                if (name.startsWith("rmnet") || name.startsWith("tun") || name.startsWith("p2p")) {
                    continue;
                }
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && a.isSiteLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
            // 兜底：任何非环回的 IPv4
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }

    /** 探测端口有没有在监听 */
    public static boolean portOpen(String host, int port) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 400);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** 调内核 external-controller 的 GET 接口，返回 body 或 null。
     *  必须用 HttpURLConnection：内核对大响应（如整组节点列表）用 chunked 编码，
     *  手写 Socket 解析会把 chunk 长度行混进 JSON 导致解析失败（v1.6 及之前自愈失效的根源）。 */
    public static String ctrlGet(String path, String secret, int readTimeoutMs) {
        try {
            // NO_PROXY：本机管理 API 绝不允许被系统代理（如 WiFi 手动代理）劫持
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                    new java.net.URL("http://127.0.0.1:" + ConfigWriter.CTRL_PORT + path)
                            .openConnection(java.net.Proxy.NO_PROXY);
            c.setConnectTimeout(1200);
            c.setReadTimeout(readTimeoutMs);
            c.setRequestProperty("Authorization", "Bearer " + (secret == null ? "" : secret));
            int code = c.getResponseCode();
            if (code >= 400) {
                logFail("GET " + path + " -> HTTP " + code, null);
                return null;
            }
            java.io.InputStream in = c.getInputStream();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0 && sb.length() < 4 * 1024 * 1024) {
                sb.append(buf, 0, n);
            }
            r.close();
            return sb.length() > 0 ? sb.toString() : null;
        } catch (Exception e) {
            logFail("GET " + path, e);
            return null;
        }
    }

    /** 调内核 external-controller 的 PUT 接口（如切换策略组选中项），返回 HTTP 状态码或 -1 */
    public static int ctrlPut(String path, String jsonBody, String secret, int readTimeoutMs) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                    new java.net.URL("http://127.0.0.1:" + ConfigWriter.CTRL_PORT + path)
                            .openConnection(java.net.Proxy.NO_PROXY);
            c.setConnectTimeout(1200);
            c.setReadTimeout(readTimeoutMs);
            c.setRequestMethod("PUT");
            c.setDoOutput(true);
            c.setRequestProperty("Authorization", "Bearer " + (secret == null ? "" : secret));
            c.setRequestProperty("Content-Type", "application/json");
            byte[] b = jsonBody == null ? new byte[0] : jsonBody.getBytes("UTF-8");
            c.setFixedLengthStreamingMode(b.length);
            java.io.OutputStream os = c.getOutputStream();
            os.write(b);
            os.flush();
            os.close();
            int code = c.getResponseCode();
            if (code >= 400) {
                logFail("PUT " + path + " -> HTTP " + code, null);
            }
            return code;
        } catch (Exception e) {
            logFail("PUT " + path, e);
            return -1;
        }
    }
}
