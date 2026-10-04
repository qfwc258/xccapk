package com.zaka.mihomotv;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 极简本地管理面板（HTTP，端口见 ConfigWriter.PANEL_PORT）。
 * 提供：订阅源 / 控制密钥 / 局域网开关的表单，以及内置 YACD 仪表盘（iframe 到 :9090/ui）。
 * 由 MihomoService 在启动时拉起，随服务存活。
 */
public class PanelServer {

    private static final String TAG = "MihomoTV";

    private final Context ctx;
    private ServerSocket ss;
    private Thread thread;
    private volatile boolean running = false;

    public PanelServer(Context ctx) {
        this.ctx = ctx.getApplicationContext();
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
            Log.i(TAG, "管理面板已启动 :" + ConfigWriter.PANEL_PORT);
        } catch (IOException e) {
            Log.w(TAG, "管理面板绑定失败: " + e);
            running = false;
            return;
        }
        while (running) {
            try {
                Socket s = ss.accept();
                handle(s);
            } catch (IOException e) {
                if (running) {
                    Log.w(TAG, "管理面板 accept: " + e);
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

            if (path.startsWith("/api/set")) {
                sendJson(s, apiSet(body));
            } else if (path.startsWith("/api/start")) {
                startCore();
                sendJson(s, json(true, "已发送启动"));
            } else if (path.startsWith("/api/stop")) {
                stopCore();
                sendJson(s, json(true, "已发送停止"));
            } else if (path.startsWith("/api/info")) {
                sendJson(s, apiInfo());
            } else {
                sendHtml(s, managementPage());
            }
        } catch (IOException e) {
            Log.w(TAG, "管理面板 handle: " + e);
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ---------------------------------------------------------- API

    private String apiSet(String body) {
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
        String yaml = ConfigWriter.build(sub, lan, secret);
        String err = ConfigWriter.write(ctx, yaml);
        if (err != null) {
            return json(false, "写入配置失败: " + err);
        }
        ctx.getSharedPreferences(MainActivity.PREF, Context.MODE_PRIVATE)
                .edit()
                .putString(MainActivity.K_SUB, sub)
                .putString(MainActivity.K_SECRET, secret)
                .putBoolean(MainActivity.K_LAN, lan)
                .apply();
        startCore();
        return json(true, "已保存并重启内核");
    }

    private String apiInfo() {
        String ip = Net.lanIp();
        String secret = ctx.getSharedPreferences(MainActivity.PREF, Context.MODE_PRIVATE)
                .getString(MainActivity.K_SECRET, ConfigWriter.DEFAULT_SECRET);
        boolean lan = ctx.getSharedPreferences(MainActivity.PREF, Context.MODE_PRIVATE)
                .getBoolean(MainActivity.K_LAN, true);
        String api = "http://" + ip + ":" + ConfigWriter.CTRL_PORT;
        return "{\"ok\":true,\"running\":" + MihomoService.isRunning()
                + ",\"lan\":" + lan
                + ",\"ip\":\"" + ip + "\""
                + ",\"api\":\"" + api + "\""
                + ",\"ui\":\"" + api + "/ui/#/" + api + "?secret=" + secret + "\""
                + ",\"secret\":\"" + secret + "\"}";
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

    // ---------------------------------------------------------- 控制内核

    private void startCore() {
        try {
            ctx.startService(new Intent(ctx, MihomoService.class)
                    .setAction(MihomoService.ACTION_START));
        } catch (Exception e) {
            Log.w(TAG, "面板启动内核失败: " + e);
        }
    }

    private void stopCore() {
        try {
            ctx.startService(new Intent(ctx, MihomoService.class)
                    .setAction(MihomoService.ACTION_STOP));
        } catch (Exception e) {
            Log.w(TAG, "面板停止内核失败: " + e);
        }
    }

    // ---------------------------------------------------------- 页面

    private String managementPage() {
        String ip = Net.lanIp();
        android.content.SharedPreferences sp = ctx.getSharedPreferences(
                MainActivity.PREF, Context.MODE_PRIVATE);
        String secret = sp.getString(MainActivity.K_SECRET, ConfigWriter.DEFAULT_SECRET);
        String sub = sp.getString(MainActivity.K_SUB, "");
        boolean lan = sp.getBoolean(MainActivity.K_LAN, true);
        String api = "http://" + ip + ":" + ConfigWriter.CTRL_PORT;
        String ui = api + "/ui/#/" + api + "?secret=" + secret;
        String subEsc = sub.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");

        StringBuilder h = new StringBuilder();
        h.append("<!doctype html><html lang=zh><head><meta charset=utf-8>");
        h.append("<meta name=viewport content=\"width=device-width,initial-scale=1\">");
        h.append("<title>Mihomo TV 管理面板</title>");
        h.append("<style>body{background:#0e1116;color:#e6edf3;font-family:-apple-system,"
                + "Segoe UI,Roboto,sans-serif;margin:0;padding:16px}");
        h.append("h1{font-size:20px;margin:0 0 4px}.muted{color:#8b98a5;font-size:12px}");
        h.append(".box{background:#161b22;border:1px solid #2a3441;border-radius:8px;"
                + "padding:14px;margin:12px 0}");
        h.append("label{display:block;font-size:13px;color:#9fb0c0;margin:8px 0 4px}");
        h.append("textarea,input[type=text]{width:100%;box-sizing:border-box;background:#1c2330;"
                + "color:#fff;border:1px solid #2a3441;border-radius:6px;padding:10px;font-size:14px}");
        h.append("button{background:#2ea043;color:#fff;border:0;border-radius:6px;"
                + "padding:10px 16px;font-size:15px;cursor:pointer;margin-right:8px}");
        h.append("code{color:#7ee787}a{color:#58a6ff}");
        h.append("iframe{width:100%;height:68vh;border:0;border-radius:8px;background:#fff}");
        h.append("</style></head><body>");
        h.append("<h1>Mihomo TV 管理面板</h1>");
        h.append("<div class=box><span class=muted>API：</span><code>").append(api)
                .append("</code> &nbsp; <span class=muted>密钥：</span><code>").append(secret)
                .append("</code><br><a href=\"").append(ui)
                .append("\" target=_blank>在新标签打开仪表盘 ↗</a></div>");
        h.append("<div class=box>");
        h.append("<label>订阅源（http(s) 地址，或整段 config.yaml）</label>");
        h.append("<textarea id=sub rows=5>").append(subEsc).append("</textarea>");
        h.append("<label>控制面板密钥（默认 ").append(ConfigWriter.DEFAULT_SECRET).append("）</label>");
        h.append("<input id=secret type=text value=\"").append(secret).append("\">");
        h.append("<label><input id=lan type=checkbox ").append(lan ? "checked" : "")
                .append("> 允许局域网代理</label>");
        h.append("<div style='margin-top:10px'><button onclick=save()>保存并应用</button>");
        h.append("<button onclick=startc() style='background:#1f6feb'>启动内核</button>");
        h.append("<button onclick=stopc() style='background:#b62324'>停止内核</button>");
        h.append("<span id=msg class=muted></span></div></div>");
        h.append("<div class=box><label class=muted>仪表盘（YACD）</label>");
        h.append("<iframe src=\"").append(ui).append("\"></iframe></div>");
        h.append("<script>");
        h.append("function post(u,d){return fetch(u,{method:'POST',"
                + "headers:{'Content-Type':'application/x-www-form-urlencoded'},body:d});}");
        h.append("function save(){var d='sub='+encodeURIComponent(document.getElementById('sub').value)"
                + "+'&secret='+encodeURIComponent(document.getElementById('secret').value)"
                + "+'&lan='+(document.getElementById('lan').checked?1:0);");
        h.append("post('/api/set',d).then(r=>r.json()).then(j=>{"
                + "document.getElementById('msg').textContent=j.msg;"
                + "setTimeout(()=>location.reload(),1200);});}");
        h.append("function startc(){post('/api/start','').then(()=>"
                + "document.getElementById('msg').textContent='已发送启动');}");
        h.append("function stopc(){post('/api/stop','').then(()=>"
                + "document.getElementById('msg').textContent='已发送停止');}");
        h.append("</script></body></html>");
        return h.toString();
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
