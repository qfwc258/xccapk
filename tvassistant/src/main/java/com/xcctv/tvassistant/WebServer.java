package com.xcctv.tvassistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

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
import java.util.UUID;

/**
 * 本地网页控制端（HTTP，端口见 ConfigWriter.PANEL_PORT）。
 * - 代理：订阅列表（可保存多个、一键切换）/ 控制密钥 / 局域网开关 / 启停（含 YACD tab）；
 * - 源下载：xcctv 源地址设置、立即下载、进度与日志；
 * - 访问密码：设置后首次访问需输入密码（Cookie 会话），网页里可改密/登出。
 * 由 CoreService 在启动时拉起，随服务存活。
 */
public class WebServer {

    private static final String TAG = "TV助手";
    private static final String COOKIE = "ta_sess";

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

    /** 会话令牌：改密码/重启服务后失效 */
    private volatile String session = UUID.randomUUID().toString();

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
            String cookie = "";

            int contentLen = 0;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                int idx = line.indexOf(':');
                if (idx > 0) {
                    String k = line.substring(0, idx).trim();
                    String v = line.substring(idx + 1).trim();
                    if (k.equalsIgnoreCase("content-length")) {
                        try {
                            contentLen = Integer.parseInt(v);
                        } catch (NumberFormatException ignored) {
                        }
                    } else if (k.equalsIgnoreCase("cookie")) {
                        cookie = v;
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

            // ---- 登录接口永远放行 ----
            if (path.startsWith("/api/login")) {
                sendJson(s, apiLogin(body));
                return;
            }

            // ---- 访问密码校验 ----
            if (needAuth() && !authed(cookie)) {
                if (path.startsWith("/api/")) {
                    sendJson(s, 401, "{\"ok\":false,\"msg\":\"请先登录\",\"auth\":true}");
                } else {
                    sendHtml(s, 200, loginHtml());
                }
                return;
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
            } else if (path.startsWith("/api/subs/list")) {
                sendJson(s, apiSubsList());
            } else if (path.startsWith("/api/subs/add")) {
                sendJson(s, apiSubsAdd(body));
            } else if (path.startsWith("/api/subs/del")) {
                sendJson(s, apiSubsDel(body));
            } else if (path.startsWith("/api/subs/use")) {
                sendJson(s, apiSubsUse(body));
            } else if (path.startsWith("/api/subs/refresh")) {
                sendJson(s, apiSubsRefresh());
            } else if (path.startsWith("/api/proxytest")) {
                sendJson(s, apiProxyTest());
            } else if (path.startsWith("/api/live")) {
                sendJson(s, apiLive());
            } else if (path.startsWith("/api/regroup")) {
                sendJson(s, apiRegroup());
            } else if (path.startsWith("/api/pass/set")) {
                sendJson(s, apiPassSet(body));
            } else if (path.startsWith("/api/pass/off")) {
                sendJson(s, apiPassOff());
            } else if (path.startsWith("/api/logout")) {
                session = UUID.randomUUID().toString();
                sendJson(s, "{\"ok\":true,\"msg\":\"已登出\"}");
            } else if (path.startsWith("/api/source/set")) {
                sendJson(s, apiSourceSet(body));
            } else if (path.startsWith("/api/source/start")) {
                sendJson(s, apiSourceStart());
            } else if (path.startsWith("/api/source/info")) {
                sendJson(s, SourceController.INSTANCE.statusJson());
            } else if (path.startsWith("/api/corelog")) {
                sendJson(s, coreLogJson());
            } else if (path.startsWith("/api/info")) {
                sendJson(s, apiCombined());
            } else {
                sendHtml(s, 200, indexHtml);
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

    // ---------------------------------------------------------- 鉴权

    private boolean needAuth() {
        return Prefs.get(ctx).getString(Prefs.K_PASS, "").length() > 0;
    }

    private boolean authed(String cookieHeader) {
        if (cookieHeader == null || cookieHeader.isEmpty()) {
            return false;
        }
        String want = COOKIE + "=" + session;
        for (String c : cookieHeader.split(";")) {
            if (c.trim().equals(want)) {
                return true;
            }
        }
        return false;
    }

    private String apiLogin(String body) {
        Map<String, String> f = parseForm(body);
        String pass = (f.get("pw") == null ? "" : f.get("pw"));
        String cur = Prefs.get(ctx).getString(Prefs.K_PASS, "");
        if (cur.isEmpty()) {
            return "{\"ok\":true,\"msg\":\"未设置密码\"}";
        }
        if (cur.equals(pass)) {
            session = UUID.randomUUID().toString();
            return "{\"ok\":true,\"msg\":\"登录成功\",\"sess\":\"" + session + "\"}";
        }
        return "{\"ok\":false,\"msg\":\"密码错误\"}";
    }

    private String apiPassSet(String body) {
        Map<String, String> f = parseForm(body);
        String p1 = (f.get("pw") == null ? "" : f.get("pw")).trim();
        if (p1.isEmpty()) {
            return json(false, "密码不能为空（要取消密码请用「关闭密码」）");
        }
        Prefs.get(ctx).edit().putString(Prefs.K_PASS, p1).apply();
        session = UUID.randomUUID().toString();
        return "{\"ok\":true,\"msg\":\"访问密码已设置，其它设备需重新登录\",\"sess\":\""
                + session + "\"}";
    }

    private String apiPassOff() {
        Prefs.get(ctx).edit().putString(Prefs.K_PASS, "").apply();
        return json(true, "访问密码已关闭，局域网内可直接访问");
    }

    /** 登录页（自包含，风格与控制端一致） */
    private String loginHtml() {
        return "<!doctype html><html lang=zh><head><meta charset=utf-8>"
                + "<meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<title>TV助手 · 登录</title><style>"
                + ":root{--bg:#0e1116;--box:#161b22;--bd:#2a3441;--fg:#e6edf3;--grn:#2ea043}"
                + "*{box-sizing:border-box}body{background:var(--bg);color:var(--fg);"
                + "font-family:-apple-system,Segoe UI,Roboto,sans-serif;display:flex;"
                + "justify-content:center;align-items:center;min-height:90vh;margin:0}"
                + ".c{background:var(--box);border:1px solid var(--bd);border-radius:12px;"
                + "padding:26px 22px;width:320px}h1{font-size:18px;margin:0 0 4px}"
                + "p{color:#8b98a5;font-size:12px;margin:0 0 14px}"
                + "input{width:100%;background:#1c2330;color:#fff;border:1px solid var(--bd);"
                + "border-radius:6px;padding:11px;font-size:15px}"
                + "button{width:100%;margin-top:12px;background:var(--grn);color:#fff;border:0;"
                + "border-radius:6px;padding:12px;font-size:15px}"
                + "#m{color:#f85149;font-size:13px;margin-top:10px;min-height:18px}</style></head><body>"
                + "<div class=c><h1>TV助手 · 控制端</h1><p>本控制端已开启访问密码</p>"
                + "<input id=pw type=password placeholder='访问密码' onkeydown='if(event.key==\"Enter\")go()'>"
                + "<button onclick=go()>进入</button><div id=m></div></div>"
                + "<script>function go(){fetch('/api/login',{method:'POST',"
                + "headers:{'Content-Type':'application/x-www-form-urlencoded'},"
                + "body:'pw='+encodeURIComponent(document.getElementById('pw').value)})"
                + ".then(r=>r.json()).then(j=>{if(j.ok){document.cookie='ta_sess='+j.sess+'; path=/';"
                + "location.href='/';}else{document.getElementById('m').textContent=j.msg;}});}"
                + "</script></body></html>";
    }

    // ---------------------------------------------------------- 订阅列表

    /** 读订阅列表（JSON 数组：n=名称 u=地址），兼容老用户：为空时用当前 K_SUB 播种 */
    private JSONArray subs() {
        SharedPreferences sp = Prefs.get(ctx);
        try {
            JSONArray a = new JSONArray(sp.getString(Prefs.K_SUBS, "[]"));
            if (a.length() > 0) {
                return a;
            }
        } catch (Exception ignored) {
        }
        String cur = sp.getString(Prefs.K_SUB, "");
        JSONArray a = new JSONArray();
        if (cur.length() > 0 && ConfigWriter.isUrl(ConfigWriter.normalize(cur))) {
            JSONObject o = new JSONObject();
            try {
                o.put("n", "我的订阅");
                o.put("u", ConfigWriter.normalize(cur));
            } catch (Exception ignored) {
            }
            a.put(o);
        }
        return a;
    }

    private void saveSubs(JSONArray a) {
        Prefs.get(ctx).edit().putString(Prefs.K_SUBS, a.toString()).apply();
    }

    private String apiSubsList() {
        JSONArray a = subs();
        String cur = Prefs.get(ctx).getString(Prefs.K_SUB_CUR,
                Prefs.get(ctx).getString(Prefs.K_SUB, ""));
        return "{\"ok\":true,\"cur\":\"" + esc(cur) + "\",\"subs\":" + a + "}";
    }

    private String apiSubsAdd(String body) {
        Map<String, String> f = parseForm(body);
        String url = ConfigWriter.normalize(f.get("u") == null ? "" : f.get("u"));
        String name = (f.get("n") == null ? "" : f.get("n")).trim();
        if (url.isEmpty()) {
            return json(false, "订阅地址不能为空");
        }
        if (!ConfigWriter.isUrl(url)) {
            return json(false, "订阅列表只存 http(s) 地址；整段 YAML 请用上方输入框直接应用");
        }
        if (name.isEmpty()) {
            try {
                name = new java.net.URL(url).getHost();
            } catch (Exception e) {
                name = "订阅" + (subs().length() + 1);
            }
        }
        JSONArray a = subs();
        // 已存在则更新名称
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && url.equals(o.optString("u"))) {
                o.remove("n");
                try {
                    o.put("n", name);
                } catch (Exception ignored) {
                }
                saveSubs(a);
                return json(true, "已更新该订阅的名称");
            }
        }
        JSONObject o = new JSONObject();
        try {
            o.put("n", name);
            o.put("u", url);
        } catch (Exception ignored) {
        }
        a.put(o);
        saveSubs(a);
        return json(true, "已添加订阅「" + name + "」");
    }

    private String apiSubsDel(String body) {
        Map<String, String> f = parseForm(body);
        String url = f.get("u") == null ? "" : f.get("u");
        JSONArray a = subs();
        JSONArray b = new JSONArray();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null || url.equals(o.optString("u"))) {
                continue;
            }
            b.put(o);
        }
        saveSubs(b);
        return json(true, "已删除");
    }

    /** 切换到某个订阅：写配置并热重启内核 */
    private String apiSubsUse(String body) {
        Map<String, String> f = parseForm(body);
        String url = f.get("u") == null ? "" : f.get("u");
        SharedPreferences sp = Prefs.get(ctx);
        JSONArray a = subs();
        boolean found = false;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && url.equals(o.optString("u"))) {
                found = true;
                break;
            }
        }
        if (!found) {
            return json(false, "该订阅不在列表中");
        }
        String secret = sp.getString(Prefs.K_SECRET, ConfigWriter.DEFAULT_SECRET);
        boolean lan = sp.getBoolean(Prefs.K_LAN, true);
        String[] r = ConfigWriter.buildSmart(ctx, url, lan, secret);
        String err = ConfigWriter.write(ctx, r[0]);
        if (err != null) {
            return json(false, "配置写入失败: " + err);
        }
        sp.edit().putString(Prefs.K_SUB, url).putString(Prefs.K_SUB_CUR, url).apply();
        hub.startProxy();
        return json(true, "已切换订阅并重启内核：" + r[1]);
    }

    /** 强制刷新订阅：重启内核即触发 App 重新抓取并清洗订阅 */
    private String apiSubsRefresh() {
        SharedPreferences sp = Prefs.get(ctx);
        String cur = sp.getString(Prefs.K_SUB, "");
        if (!ConfigWriter.isUrl(ConfigWriter.normalize(cur))) {
            return json(false, "当前不是订阅模式（自定义 YAML 无需刷新）");
        }
        hub.startProxy();
        return json(true, "正在重新下载订阅并重启内核…请稍后看概览页内核日志");
    }

    /**
     * 实时连接状态：聚合内核 /proxies/自动选择（当前节点）与 /connections（连接数/流量）。
     * 速率由前端按两次轮询差值计算。
     */
    private String apiLive() {
        String secret = Prefs.get(ctx).getString(Prefs.K_SECRET, ConfigWriter.DEFAULT_SECRET);
        long up = 0, down = 0;
        int conns = 0, delay = 0;
        String node = "";

        // 1) 组当前节点（最准）
        String g = Net.ctrlGet("/proxies/" + enc("自动选择"), secret, 3000);
        if (g != null) {
            try {
                JSONObject o = new JSONObject(g);
                node = o.optString("now", "");
                JSONArray hist = o.optJSONArray("history");
                if (hist != null && hist.length() > 0) {
                    delay = hist.getJSONObject(hist.length() - 1).optInt("delay", 0);
                }
            } catch (Exception ignored) {
            }
        }

        // 2) 连接数与累计流量
        String body = ctrlGet("/connections", secret);
        if (body != null) {
            try {
                JSONObject o = new JSONObject(body);
                up = o.optLong("uploadTotal", 0);
                down = o.optLong("downloadTotal", 0);
                JSONArray cs = o.optJSONArray("connections");
                conns = cs == null ? 0 : cs.length();
                if ((node == null || node.isEmpty()) && conns > 0) {
                    java.util.HashMap<String, Integer> cnt = new java.util.HashMap<String, Integer>();
                    for (int i = 0; i < conns; i++) {
                        JSONObject c = cs.optJSONObject(i);
                        if (c == null) {
                            continue;
                        }
                        JSONArray chain = c.optJSONArray("chains");
                        if (chain == null) {
                            continue;
                        }
                        for (int k = 0; k < chain.length(); k++) {
                            String seg = chain.optString(k, "");
                            if (seg.length() > 0 && !"PROXY".equals(seg) && !"自动选择".equals(seg)) {
                                Integer v = cnt.get(seg);
                                cnt.put(seg, v == null ? 1 : v + 1);
                                break;
                            }
                        }
                    }
                    int best = 0;
                    for (Map.Entry<String, Integer> e : cnt.entrySet()) {
                        if (e.getValue() > best) {
                            best = e.getValue();
                            node = e.getKey();
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return "{\"ok\":true,\"running\":" + hub.isProxyRunning()
                + ",\"conns\":" + conns
                + ",\"up\":" + up + ",\"down\":" + down
                + ",\"delay\":" + delay
                + ",\"retest\":\"" + esc(CoreService.lastRetestInfo) + "\""
                + ",\"node\":\"" + esc(node == null ? "" : node) + "\"}";
    }

    /** 手动触发「自动选择」整组重测：坏节点测速失败被淘汰，url-test 改选最快可用节点 */
    private String apiRegroup() {
        if (!hub.isProxyRunning()) {
            return json(false, "代理未启动");
        }
        String secret = Prefs.get(ctx).getString(Prefs.K_SECRET, ConfigWriter.DEFAULT_SECRET);
        String r = Net.ctrlGet("/group/" + enc("自动选择")
                + "/delay?url=" + enc("https://www.gstatic.com/generate_204")
                + "&timeout=5000", secret, 20000);
        if (r == null) {
            return json(false, "重测无应答（内核可能未就绪），稍后再试");
        }
        String node = "";
        int best = Integer.MAX_VALUE;
        int okCnt = 0;
        try {
            JSONObject o = new JSONObject(r);
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                int d = o.optInt(k, 0);
                if (d > 0) {
                    okCnt++;
                    if (d < best) {
                        best = d;
                        node = k;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (okCnt == 0) {
            CoreService.lastRetestInfo = "0 个节点可用";
            return json(false, "重测完成：无任何节点可用（订阅已失效/被墙，请更新订阅或更换机场）");
        }
        CoreService.lastRetestInfo = "可用 " + okCnt + " 个，最快「" + node + "」"
                + (best < Integer.MAX_VALUE ? " " + best + "ms" : "");
        return json(true, "重测完成：" + okCnt + " 个节点可用，最快「" + node + "」"
                + (best < Integer.MAX_VALUE ? " " + best + "ms" : "") + "，已自动切换");
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /** 内核控制接口原始 GET，返回 body 或 null */
    private String ctrlGet(String path, String secret) {
        java.net.Socket s = null;
        try {
            s = new java.net.Socket();
            s.connect(new java.net.InetSocketAddress("127.0.0.1", ConfigWriter.CTRL_PORT), 800);
            s.setSoTimeout(1500);
            java.io.OutputStream os = s.getOutputStream();
            String req = "GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                    + "Authorization: Bearer " + (secret == null ? "" : secret) + "\r\n"
                    + "Connection: close\r\n\r\n";
            os.write(req.getBytes(StandardCharsets.UTF_8));
            os.flush();
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
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
                if (s != null) {
                    s.close();
                }
            } catch (Exception ignored) {
            }
        }
    }

    /** 连通性测试：经本机 7890 代理访问 gstatic generate_204 */
    private String apiProxyTest() {
        if (!hub.isProxyRunning()) {
            return "{\"ok\":false,\"msg\":\"代理未启动，请先在「代理」页点启动\"}";
        }
        long t0 = System.currentTimeMillis();
        try {
            java.net.Proxy p = new java.net.Proxy(java.net.Proxy.Type.HTTP,
                    new java.net.InetSocketAddress("127.0.0.1", ConfigWriter.MIXED_PORT));
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                    new java.net.URL("https://www.gstatic.com/generate_204").openConnection(p);
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            int code = c.getResponseCode();
            long ms = System.currentTimeMillis() - t0;
            if (code == 204 || code == 200) {
                return "{\"ok\":true,\"msg\":\"✓ 代理连通（" + ms + "ms，HTTP " + code + "）\"}";
            }
            return "{\"ok\":false,\"msg\":\"代理可达但应答异常 HTTP " + code + "（" + ms + "ms）\"}";
        } catch (Exception e) {
            long ms = System.currentTimeMillis() - t0;
            String lastErr = lastCoreDialError();
            return "{\"ok\":false,\"msg\":\"✗ 测试失败（" + ms + "ms）："
                    + String.valueOf(e.getMessage()).replace("\"", "'") + "」"
                    + ",\"kerr\":\"" + esc(lastErr) + "\"}";
        }
    }

    /** 从内核日志里取最后一条出站错误（截 error: 之后的内容，给用户看原因） */
    private static String lastCoreDialError() {
        String tail = CoreService.tail(30);
        String last = "";
        for (String ln : tail.split("\n")) {
            int i = ln.indexOf("error:");
            if (i >= 0) {
                last = ln.substring(i + 6).trim();
            }
        }
        if (last.length() > 160) {
            last = last.substring(0, 160) + "…";
        }
        return last.replace("'", "").replace("\n", " ");
    }

    // ---------------------------------------------------------- 其它 API

    private String apiProxySet(String body) {
        Map<String, String> f = parseForm(body);
        String sub = (f.get("sub") == null ? "" : f.get("sub")).trim();
        String secret = (f.get("secret") == null ? "" : f.get("secret")).trim();
        boolean lan = "1".equals(f.get("lan")) || "on".equals(f.get("lan"))
                || "true".equals(f.get("lan"));
        boolean save = "1".equals(f.get("save"));
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
        // URL 输入且勾选「存入订阅列表」→ 自动收进列表
        if (save && ConfigWriter.isUrl(sub)) {
            try {
                apiSubsAdd("u=" + java.net.URLEncoder.encode(sub, "UTF-8"));
            } catch (Exception ignored) {
            }
        }
        return json(true, "已保存并重启内核");
    }

    private boolean storageOk() {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        if (Build.VERSION.SDK_INT >= 23) {
            return ctx.checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE")
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        return true;
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

    private String apiSourceStart() {
        String url = Prefs.get(ctx).getString(Prefs.K_SRC_URL, "");
        if (url.isEmpty()) {
            return json(false, "请先填写源地址");
        }
        if (!storageOk()) {
            SourceController.INSTANCE.permFail();
            return json(false, "缺少「所有文件访问」权限：请在电视上打开 TV助手 按提示授权，"
                    + "或到 系统设置→应用→TV助手→权限 里开启");
        }
        SourceController.INSTANCE.start(ctx, url);
        return json(true, "已触发下载");
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

    private String coreLogJson() {
        String log = CoreService.tail(30).replace("\"", "'");
        return "{\"ok\":true,\"log\":\"" + log + "\",\"err\":\""
                + CoreService.lastError().replace("\"", "'") + "\"}";
    }

    private String apiCombined() {
        String ip = Net.lanIp();
        SharedPreferences sp = Prefs.get(ctx);
        String secret = sp.getString(Prefs.K_SECRET, ConfigWriter.DEFAULT_SECRET);
        boolean lan = sp.getBoolean(Prefs.K_LAN, true);
        boolean hasPass = sp.getString(Prefs.K_PASS, "").length() > 0;
        String api = "http://" + ip + ":" + ConfigWriter.CTRL_PORT;
        String err = CoreService.lastError();
        return "{\"ok\":true"
                + ",\"ip\":\"" + ip + "\""
                + ",\"panel\":" + ConfigWriter.PANEL_PORT
                + ",\"proxyRunning\":" + hub.isProxyRunning()
                + ",\"lan\":" + lan
                + ",\"hasPass\":" + hasPass
                + ",\"storage\":" + storageOk()
                + ",\"proxyApi\":\"" + api + "\""
                + ",\"proxyUi\":\"" + api + "/ui/#/" + api + "?secret=" + secret + "\""
                + ",\"secret\":\"" + secret + "\""
                + ",\"subCur\":\"" + esc(sp.getString(Prefs.K_SUB, "")) + "\""
                + ",\"srcUrl\":\"" + sp.getString(Prefs.K_SRC_URL, "") + "\""
                + ",\"coreErr\":\"" + err.replace("\"", "'").replace("\n", " ") + "\""
                + ",\"coreLog\":\"" + CoreService.tail(8).replace("\"", "'")
                        .replace("\n", "\\n") + "\""
                + ",\"src\":" + SourceController.INSTANCE.statusJson() + "}";
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\"", "'");
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

    private void sendJson(Socket s, String body) throws IOException {
        sendJson(s, 200, body);
    }

    private void sendJson(Socket s, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + " " + (code == 200 ? "OK" : "Auth") + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Content-Length: " + b.length
                + "\r\nConnection: close\r\n\r\n";
        OutputStream os = s.getOutputStream();
        os.write(head.getBytes(StandardCharsets.UTF_8));
        os.write(b);
        os.flush();
    }

    private void sendHtml(Socket s, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + " OK\r\nContent-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + b.length + "\r\nConnection: close\r\n\r\n";
        OutputStream os = s.getOutputStream();
        os.write(head.getBytes(StandardCharsets.UTF_8));
        os.write(b);
        os.flush();
    }
}
