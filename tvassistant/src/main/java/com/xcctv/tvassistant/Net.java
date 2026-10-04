package com.xcctv.tvassistant;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.Collections;
import java.util.Enumeration;

public final class Net {

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

    /** 调内核 external-controller 的 GET 接口，返回 body（已去掉 HTTP 头）或 null */
    public static String ctrlGet(String path, String secret, int readTimeoutMs) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", ConfigWriter.CTRL_PORT), 800);
            s.setSoTimeout(readTimeoutMs);
            java.io.OutputStream os = s.getOutputStream();
            String req = "GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                    + "Authorization: Bearer " + (secret == null ? "" : secret) + "\r\n"
                    + "Connection: close\r\n\r\n";
            os.write(req.getBytes("UTF-8"));
            os.flush();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(s.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String l;
            boolean headDone = false;
            while ((l = r.readLine()) != null) {
                if (!headDone) {
                    if (l.isEmpty()) {
                        headDone = true;
                    }
                    continue;
                }
                sb.append(l).append('\n');
                if (sb.length() > 4 * 1024 * 1024) {
                    break;
                }
            }
            return sb.length() > 0 ? sb.toString() : null;
        } catch (Exception e) {
            return null;
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }
}
