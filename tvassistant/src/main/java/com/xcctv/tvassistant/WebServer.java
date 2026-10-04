package com.xcctv.tvassistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 极简本地网页控制端（HTTP，端口见 ConfigWriter.PANEL_PORT）。
 * 手机浏览器访问后，可设置/控制两件事：
 *   - 代理：订阅源 / 控制密钥 / 局域网开关 / 启停（含 YACD 仪表盘 tab）；
 *   - 源下载：xcctv 源地址设置、立即下载、进度与日志。
 * 由 CoreService 在启动时拉起，随服务存活。免密直连（同局域网即可访问）。
 */
public class WebServer {

    private static final String TAG = "TV助手";

    /** CoreService 实现，供网页端调度代理启停。 */
    public interface Control {
        void applyProxy(String sub, String secret, boolean lan);

        void startProxy();

        void stopProxy();

        boolean isProxyRunning();
    }

    private final Context ctx;
    private final Control hub;
    private ServerSocket ss;
    private Thread thread;
    private volatile boolean running = false;

    private final String indexHtml;

    public WebServer(Context ctx, Control hub) {
        this.ctx = ctx.getApplicationContext();
        this.hub = hub;
        this.indexHtml = loadAsset("index.html");
    }

    private String loadAsset(String name) {
        try {
            InputStream in = ctx.getAssets().open(name);
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) {
                b.append(l).append('\n');
            }
            in.close();
            return b.toString();
        } catch (IOException e) {
            Log.w(TAG, "读取 index.html 失败: " + e);
            return "<h1>控制页面资源缺失</h1>";
        }
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        try {
            if (ss != null) {
                ss.close();
            }
        } catch (IOException ignored) {
        }
    }

    private void loop() {
        try {
            ss = new ServerSocket(ConfigWriter.PANEL_PORT);
            Log.i(TAG, "网页控制端已启动 :" + ConfigWriter.PANEL_PORT);
        } catch (IOException e) {
            Log.w(TAG, "网页控制端绑定失败: " + e);
            running = false;
            return;
        }
        while (running) {
            try {
                Socket s = ss.accept();
                handle(s);
            } catch (IOException e) {
                if (running) {
                    Log.w(TAG, "网页控制端 accept: " + e);
                }
            }
        }
    }

    private void handle(Socket s) {
        try {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String first = in.readLine();
            if (first == null) {
                s.close();
                return;
            }
            String[] parts = first.split(" ");
            String method = parts[0];
            String path = parts.length > 1 ? parts[1] : "/";

            int contentLen = 0;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                int idx = line.indexOf(':');
                if (idx > 0 && line.substring(0, idx).trim().equalsIgnoreCase("content-length")) {
                    try {
                        contentLen = Integer.parseInt(line.substring(idx + 1).trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }

            String body = "";
            if (contentLen > 0 && "POST".equals(method)) {
                char[] cbuf = new char[contentLen];
                int got = 0;
                while (got < contentLen) {
                    int r = in.read(cbuf, got, contentLen - got);
                    if (r < 0) {
                        break;
                    }
                    got += r;
                }
                body = new String(cbuf, 0, got);
            }

            if (path.startsWith("/api/proxy/set")) {
                sendJson(s, apiProxySet(body));
            } else if (path.startsWith("/api/proxy/start")) {
                hub.startProxy();
                sendJson(s, json(true, "已发送启动"));
            } else if (path.startsWith("/api/proxy/stop")) {
                hub.stopProxy();
                sendJson(s, json(true, "已发送停止"));
            } else if (path.startsWith("/api/proxy/info")) {
                sendJson(s, apiProxyInfo());
            } else if (path.startsWith("/api/source/set")) {
                sendJson(s, apiSourceSet(body));
            } else if (path.startsWith("/api/source/start")) {
                SourceController.INSTANCE.start(ctx, Prefs.get(ctx).getString(Prefs.K_SRC_URL, ""));
                sendJson(s, json(true, "已触发下载"));
            } else if (path.startsWith("/api/source/info")) {
                sendJson(s, SourceController.INSTANCE.statusJson());
            } else if (path.startsWith("/api/info")) {
                sendJson(s, apiCombined());
            } else {
                sendHtml(s, indexHtml);
            }
        } catch (IOException e) {
            Log.w(TAG, "网页控制端 handle: " + e);
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ---------------------------------------------------------- API

    private String apiProxySet(String body) {
        Map<String, String> f = parseForm(body);
        String sub = (f.get("sub") == null ? "" : f.get("sub")).trim();
        String secret = (f.get("secret") == null ? "" : f.get("secret")).trim();
        boolean lan = "1".equals(f.get("lan")) || "on".equals(f.get("lan"))
                || "true".equals(f.get("lan"));
        if (secret.isEmpty()) {
            secret = ConfigWriter.DEFAULT_SECRET;
        }
        if (sub.isEmpty()) {
            return json(false, "订阅源不能为空");
        }
        sub = ConfigWriter.normalize(sub);
        if (!ConfigWriter.isUrl(sub) && !ConfigWriter.looksLikeYaml(sub)) {
            return json(false, "订阅源需为 http(s) 地址或带 proxies: 的 YAML");
        }
        hub.applyProxy(sub, secret, lan);
        return json(true, "已保存并重启内核");
    }

    private String apiProxyInfo() {
        String ip = Net.lanIp();
        SharedPreferences sp = Prefs.get(ctx);
        String secret = sp.getString(Prefs.K_SECRET, ConfigWriter.DEFAULT_SECRET);
        boolean lan = sp.getBoolean(Prefs.K_LAN, true);
        String api = "http://" + ip + ":" + ConfigWriter.CTRL_PORT;
        return "{\"ok\":true,\"running\":" + hub.isProxyRunning()
                + ",\"lan\":" + lan
                + ",\"ip\":\"" + ip + "\""
                + ",\"api\":\"" + api + "\""
                + ",\"ui\":\"" + api + "/ui/#/" + api + "?secret=" + secret + "\""
                + ",\"secret\":\"" + secret + "\"}";
    }

    private String apiSourceSet(String body) {
        Map<String, String> f = parseForm(body);
        String url = (f.get("url") == null ? "" : f.get("url")).trim();
        if (url.isEmpty()) {
            return json(false, "源地址不能为空");
        }
        Prefs.get(ctx).edit().putString(Prefs.K_SRC_URL, url).apply();
        return json(true, "已保存源地址");
    }

    private String apiCombined() {
        String ip = Net.lanIp();
        SharedPreferences sp = Prefs.get(ctx);
        String secret = sp.getString(Prefs.K_SECRET, ConfigWriter.DEFAULT_SECRET);
        boolean lan = sp.getBoolean(Prefs.K_LAN, true);
        String api = "http://" + ip + ":" + ConfigWriter.CTRL_PORT;
        return "{\"ok\":true"
                + ",\"ip\":\"" + ip + "\""
                + ",\"panel\":" + ConfigWriter.PANEL_PORT
                + ",\"proxyRunning\":" + hub.isProxyRunning()
                + ",\"lan\":" + lan
                + ",\"proxyApi\":\"" + api + "\""
                + ",\"proxyUi\":\"" + api + "/ui/#/" + api + "?secret=" + secret + "\""
                + ",\"secret\":\"" + secret + "\""
                + ",\"srcUrl\":\"" + sp.getString(Prefs.K_SRC_URL, "") + "\""
                + ",\"src\":" + SourceController.INSTANCE.statusJson() + "}";
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> m = new HashMap<String, String>();
        if (body == null || body.isEmpty()) {
            return m;
        }
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            try {
                String k = URLDecoder.decode(pair.substring(0, eq), "UTF-8");
                String v = URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                m.put(k, v);
            } catch (Exception ignored) {
            }
        }
        return m;
    }

    private static String json(boolean ok, String msg) {
        return "{\"ok\":" + ok + ",\"msg\":\"" + msg.replace("\"", "'") + "\"}";
    }

    // ---------------------------------------------------------- 响应

    private static void sendJson(Socket s, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n"
                + "Access-Control-Allow-Origin: *\r\nContent-Length: " + b.length
                + "\r\nConnection: close\r\n\r\n";
        OutputStream os = s.getOutputStream();
        os.write(head.getBytes(StandardCharsets.UTF_8));
        os.write(b);
        os.flush();
    }

    private static void sendHtml(Socket s, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + b.length + "\r\nConnection: close\r\n\r\n";
        OutputStream os = s.getOutputStream();
        os.write(head.getBytes(StandardCharsets.UTF_8));
        os.write(b);
        os.flush();
    }
}
