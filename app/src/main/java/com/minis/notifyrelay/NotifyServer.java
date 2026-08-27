package com.minis.notifyrelay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.*;

public class NotifyServer {
    private static final String TAG = "NotifyRelay";
    private static NotifyServer shared;

    /** Activity 与 Service 共享同一台HTTP/LAN server，杜绝端口竞争。 */
    public static synchronized NotifyServer getShared(Context ctx) {
        if (shared == null) {
            shared = new NotifyServer(ctx.getApplicationContext());
            shared.start();
        }
        return shared;
    }
    private static final String CHANNEL_ID = "panel_notify";
    private static final String GROUP_KEY = "notify_relay_group";
    private static final int NOTIF_ID_BASE = 9000;
    private static final int SUMMARY_ID = 8999;
    private int notifIdCounter = 0;

    private final Context context;
    private ServerSocket serverSocket;
    private Thread serverThread;
    private volatile boolean running = false;
    private final List<String> recent = Collections.synchronizedList(new ArrayList<>());
    private long startTime;
    private int port = 9531;
    private LanPeerManager lan;

    public NotifyServer(Context ctx) {
        context = ctx;
        SharedPreferences prefs = ctx.getSharedPreferences("notify_relay", Context.MODE_PRIVATE);
        port = prefs.getInt("port", 9531);
        ensureChannel();
        loadRecent(prefs);
    }

    public int getPort() { return port; }
    public int broadcastFile(File file, String mime) { return lan == null ? 0 : lan.broadcastFile(file, mime); }

    private void ensureChannel() {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel chan = new NotificationChannel(CHANNEL_ID, "面板通知", NotificationManager.IMPORTANCE_HIGH);
            chan.enableVibration(true);
            chan.enableLights(true);
            chan.setShowBadge(true);
            chan.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(chan);
        }
    }

    @SuppressWarnings("unchecked")
    private void loadRecent(SharedPreferences prefs) {
        try {
            Set<String> saved = prefs.getStringSet("recent_logs", null);
            if (saved != null) {
                List<String> list = new ArrayList<>(saved);
                Collections.sort(list, Collections.reverseOrder());
                recent.addAll(list);
                while (recent.size() > 200) recent.remove(recent.size() - 1);
            }
        } catch (Exception e) {
            Log.w(TAG, "loadRecent: " + e.getMessage());
        }
    }

    private void saveRecent() {
        try {
            SharedPreferences prefs = context.getSharedPreferences("notify_relay", Context.MODE_PRIVATE);
            Set<String> set = new LinkedHashSet<>(recent);
            prefs.edit().putStringSet("recent_logs", set).commit();
        } catch (Exception e) {
            Log.w(TAG, "saveRecent: " + e.getMessage());
        }
    }

    private void addRecent(String entry) {
        recent.add(0, entry);
        while (recent.size() > 200) recent.remove(recent.size() - 1);
        saveRecent();
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        startTime = System.currentTimeMillis();
        serverThread = new Thread(() -> {
            int tryPort = port;
            for (int attempt = 0; attempt < 20; attempt++) {
                try {
                    serverSocket = new ServerSocket(tryPort, 50, InetAddress.getByName("0.0.0.0"));
                    port = tryPort;
                    SharedPreferences prefs = context.getSharedPreferences("notify_relay", Context.MODE_PRIVATE);
                    String key = prefs.getString("lan_key", "");
                    if (key.length() < 4) {
                        key = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
                        prefs.edit().putString("lan_key", key).commit();
                    }
                    lan = new LanPeerManager(context, port, key);
                    lan.start();
                    new FileTransferServer(context, port + 2, key).start();
                    Log.i(TAG, "HTTP server on 0.0.0.0:" + port);
                    break;
                } catch (IOException e) {
                    Log.w(TAG, "Port " + tryPort + " in use, trying " + (tryPort + 1));
                    tryPort++;
                }
            }
            if (serverSocket == null) {
                Log.e(TAG, "No available port!");
                running = false;
                return;
            }
            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    new Thread(() -> handleClient(client)).start();
                } catch (IOException e) {
                    if (running) Log.e(TAG, "Accept: " + e.getMessage());
                }
            }
        });
        serverThread.start();
    }

    public synchronized void stop() {
        running = false;
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException e) {}
        }
    }

    public synchronized boolean changePort(int newPort) {
        // 先测试新端口能不能用
        try (ServerSocket test = new ServerSocket(newPort)) {
            // 端口可用
        } catch (IOException e) {
            Log.e(TAG, "Port " + newPort + " not available: " + e.getMessage());
            return false;
        }
        stop();
        port = newPort;
        SharedPreferences prefs = context.getSharedPreferences("notify_relay", Context.MODE_PRIVATE);
        prefs.edit().putInt("port", newPort).commit();
        start();
        return true;
    }

    private void handleClient(Socket client) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), "UTF-8"));
            String requestLine = reader.readLine();
            if (requestLine == null) { client.close(); return; }
            String[] parts = requestLine.split(" ");
            String method = parts[0];
            String path = parts.length > 1 ? parts[1] : "/";
            int contentLength = 0;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                String lower = line.toLowerCase();
                if (lower.startsWith("content-length:")) {
                    try { contentLength = Integer.parseInt(lower.substring(15).trim()); } catch (Exception e) {}
                }
            }
            char[] bodyChars = new char[contentLength];
            if (contentLength > 0) reader.read(bodyChars);
            String body = new String(bodyChars);
            String response;
            String contentType = "application/json";

            // CORS preflight
            if ("OPTIONS".equals(method)) {
                String header = "HTTP/1.1 204 No Content\r\n"
                    + "Access-Control-Allow-Origin: *\r\n"
                    + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                    + "Access-Control-Allow-Headers: Content-Type\r\n"
                    + "Content-Length: 0\r\n\r\n";
                OutputStream os = client.getOutputStream();
                os.write(header.getBytes("UTF-8"));
                os.flush();
                client.close();
                return;
            }

            if ("POST".equals(method) && path.equals("/file")) {
                try {
                    JSONObject json = new JSONObject(body);
                    String key = json.optString("relay_key", "");
                    String localKey = context.getSharedPreferences("notify_relay", Context.MODE_PRIVATE).getString("lan_key", "");
                    String name = json.optString("name", "received_file").replaceAll("[^a-zA-Z0-9._-]", "_");
                    String data64 = json.optString("data", "");
                    if (!localKey.equals(key)) response = "{\"ok\":false,\"error\":\"pair key required\"}";
                    else if (data64.length() > 28 * 1024 * 1024) response = "{\"ok\":false,\"error\":\"file too large (max 20MB)\"}";
                    else {
                        byte[] bytes = Base64.getDecoder().decode(data64);
                        File dir = new File(context.getFilesDir(), "received"); dir.mkdirs();
                        File file = new File(dir, System.currentTimeMillis() + "_" + name);
                        try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); }
                        sendNotification("📥 收到局域网文件", name + " (" + bytes.length / 1024 + " KB)");
                        addRecent(new SimpleDateFormat("MM-dd HH:mm:ss").format(new Date()) + " | 📥 文件 | " + name);
                        response = "{\"ok\":true,\"name\":\"" + esc(name) + "\"}";
                    }
                } catch (Exception e) { response = "{\"ok\":false,\"error\":\"file receive failed\"}"; }
            } else if ("GET".equals(method) && path.startsWith("/background")) {
                SharedPreferences prefs = context.getSharedPreferences("notify_relay", Context.MODE_PRIVATE);
                String bgPath = prefs.getString("local_bg", "");
                File bg = bgPath.isEmpty() ? null : new File(bgPath);
                if (bg == null || !bg.exists()) {
                    response = "";
                    contentType = "text/plain";
                } else {
                    byte[] img = new byte[(int) bg.length()];
                    try (InputStream in = new FileInputStream(bg)) { int off=0,n; while(off<img.length && (n=in.read(img,off,img.length-off))>0) off+=n; }
                    String header = "HTTP/1.1 200 OK\r\nContent-Type: " + prefs.getString("local_bg_mime", "image/jpeg") + "\r\nContent-Length: " + img.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
                    OutputStream os = client.getOutputStream(); os.write(header.getBytes("UTF-8")); os.write(img); os.flush(); return;
                }
            } else if ("POST".equals(method) && path.equals("/recent/delete")) {
                try {
                    int index = new JSONObject(body).getInt("index");
                    synchronized (recent) {
                        if (index >= 0 && index < recent.size()) { recent.remove(index); saveRecent(); response = "{\"ok\":true}"; }
                        else response = "{\"ok\":false,\"error\":\"not found\"}";
                    }
                } catch (Exception e) { response = "{\"ok\":false,\"error\":\"invalid index\"}"; }
            } else if ("POST".equals(method) && path.equals("/recent/clear")) {
                synchronized (recent) { recent.clear(); saveRecent(); }
                response = "{\"ok\":true}";
            } else if ("GET".equals(method) && path.equals("/peers")) {
                response = "{\"ok\":true,\"peers\":" + (lan == null ? "[]" : lan.peersJson()) + "}";
            } else if ("GET".equals(method) && path.equals("/appearance")) {
                SharedPreferences prefs = context.getSharedPreferences("notify_relay", Context.MODE_PRIVATE);
                response = "{\"theme\":\"" + esc(prefs.getString("ui_theme", "glass")) + "\",\"background\":\"" + esc(prefs.getString("ui_background", "")) + "\"}";
            } else if ("POST".equals(method) && path.equals("/appearance")) {
                try {
                    JSONObject json = new JSONObject(body);
                    String theme = json.optString("theme", "glass");
                    String bg = json.optString("background", "").trim();
                    if (bg.length() > 500 || !(bg.isEmpty() || bg.startsWith("https://") || bg.startsWith("http://"))) {
                        response = "{\"ok\":false,\"error\":\"background requires http(s) URL\"}";
                    } else {
                        context.getSharedPreferences("notify_relay", Context.MODE_PRIVATE).edit().putString("ui_theme", theme).putString("ui_background", bg).commit();
                        response = "{\"ok\":true}";
                    }
                } catch (Exception e) { response = "{\"ok\":false,\"error\":\"invalid appearance\"}"; }
            } else if ("POST".equals(method) && path.equals("/lan/key")) {
                try {
                    JSONObject json = new JSONObject(body);
                    String key = json.optString("key", "").trim();
                    if (key.length() < 4) response = "{\"ok\":false,\"error\":\"key too short\"}";
                    else {
                        context.getSharedPreferences("notify_relay", Context.MODE_PRIVATE).edit().putString("lan_key", key).commit();
                        if (lan != null) lan.setGroupKey(key);
                        response = "{\"ok\":true}";
                    }
                } catch (Exception e) { response = "{\"ok\":false,\"error\":\"invalid key\"}"; }
            } else if ("POST".equals(method) && path.equals("/broadcast")) {
                try {
                    JSONObject json = new JSONObject(body);
                    String title = json.optString("title", "通知");
                    String content = json.optString("body", json.optString("content", "通知"));
                    String key = json.optString("relay_key", "");
                    String localKey = context.getSharedPreferences("notify_relay", Context.MODE_PRIVATE).getString("lan_key", "minis-local");
                    if (!localKey.equals(key)) response = "{\"ok\":false,\"error\":\"pair key required\"}";
                    else response = "{\"ok\":true,\"sent\":" + (lan == null ? 0 : lan.broadcast(title, content)) + "}";
                } catch (Exception e) { response = "{\"ok\":false,\"error\":\"invalid request\"}"; }
            } else if ("POST".equals(method) && path.equals("/port")) {
                try {
                    JSONObject json = new JSONObject(body);
                    int newPort = json.getInt("port");
                    int oldPort = port;
                    boolean ok = changePort(newPort);
                    if (ok) {
                        response = "{\"ok\":true,\"old_port\":" + oldPort + ",\"new_port\":" + newPort + "}";
                    } else {
                        response = "{\"ok\":false,\"error\":\"port " + newPort + " not available\"}";
                    }
                } catch (Exception e) {
                    response = "{\"ok\":false,\"error\":\"no port field\"}";
                }
            } else if ("POST".equals(method)) {
                int sent = 0, failed = 0;
                try {
                    JSONObject json = new JSONObject(body);
                    // 非本机请求必须携带局域网配对密钥；本机面板脚本无需改动。
                    boolean local = client.getInetAddress().isLoopbackAddress();
                    String localKey = context.getSharedPreferences("notify_relay", Context.MODE_PRIVATE).getString("lan_key", "minis-local");
                    if (!local && !localKey.equals(json.optString("relay_key", ""))) {
                        response = "{\"ok\":false,\"error\":\"pair key required\"}";
                        byte[] denied = response.getBytes("UTF-8");
                        OutputStream os = client.getOutputStream();
                        String hdr = "HTTP/1.1 403 Forbidden\r\nContent-Type: application/json\r\nContent-Length: " + denied.length + "\r\nConnection: close\r\n\r\n";
                        os.write(hdr.getBytes("UTF-8")); os.write(denied); os.flush(); return;
                    }
                    if (json.has("notifications")) {
                        JSONArray arr = json.getJSONArray("notifications");
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject item = arr.getJSONObject(i);
                            String t = item.optString("title", item.optString("name", "通知"));
                            String c = item.optString("body", item.optString("content", item.optString("text", item.optString("message", "通知"))));
                            if (c.length() > 200) c = c.substring(0, 200);
                            boolean ok = sendNotification(t, c);
                            if (ok) sent++; else failed++;
                            String ts = new SimpleDateFormat("MM-dd HH:mm:ss").format(new Date());
                            addRecent(ts + " | " + (ok ? "✅" : "❌") + " " + t + " | " + c);
                        }
                    } else {
                        String t = json.optString("title", json.optString("name", "通知"));
                        String c = json.optString("body", json.optString("content", json.optString("text", json.optString("message", "通知"))));
                        if (c.length() > 200) c = c.substring(0, 200);
                        boolean ok = sendNotification(t, c);
                        if (ok) sent++; else failed++;
                        String ts = new SimpleDateFormat("MM-dd HH:mm:ss").format(new Date());
                        addRecent(ts + " | " + (ok ? "✅" : "❌") + " " + t + " | " + c);
                    }
                } catch (Exception e) {
                    String t = getJson(body, "title");
                    if (t == null) t = getJson(body, "name");
                    if (t == null) t = "通知";
                    String c = getJson(body, "body");
                    if (c == null) c = getJson(body, "content");
                    if (c == null) c = getJson(body, "text");
                    if (c == null) c = getJson(body, "message");
                    if (c == null) c = "通知";
                    if (c.length() > 200) c = c.substring(0, 200);
                    boolean ok = sendNotification(t, c);
                    if (ok) sent++; else failed++;
                    String ts = new SimpleDateFormat("MM-dd HH:mm:ss").format(new Date());
                    addRecent(ts + " | " + (ok ? "✅" : "❌") + " " + t + " | " + c);
                }
                response = "{\"ok\":" + (failed == 0) + ",\"sent\":" + sent + ",\"failed\":" + failed + "}";
            } else if ("GET".equals(method)) {
                if (path.equals("/health")) {
                    long uptime = (System.currentTimeMillis() - startTime) / 1000;
                    response = "{\"status\":\"ok\",\"service\":\"notify-relay\",\"port\":" + port + ",\"recent_count\":" + recent.size() + ",\"uptime\":" + uptime + ",\"lan_ip\":\"" + getLanIp() + "\"}";
                } else if (path.equals("/recent")) {
                    StringBuilder sb = new StringBuilder("{\"count\":").append(recent.size()).append(",\"notifications\":[");
                    for (int i = 0; i < recent.size(); i++) {
                        if (i > 0) sb.append(",");
                        sb.append("\"").append(esc(recent.get(i))).append("\"");
                    }
                    sb.append("]}");
                    response = sb.toString();
                } else if (path.equals("/") || path.equals("/index.html")) {
                    response = HTML_PAGE;
                    contentType = "text/html; charset=utf-8";
                } else {
                    response = "{\"error\":\"not found\"}";
                }
            } else {
                response = "{\"error\":\"method not allowed\"}";
            }
            byte[] respBytes = response.getBytes("UTF-8");
            String header = "HTTP/1.1 200 OK\r\nContent-Type: " + contentType + "\r\nContent-Length: " + respBytes.length + "\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n";
            OutputStream os = client.getOutputStream();
            os.write(header.getBytes("UTF-8"));
            os.write(respBytes);
            os.flush();
        } catch (Exception e) {
            Log.e(TAG, "Client: " + e.getMessage());
        } finally {
            try { client.close(); } catch (IOException e) {}
        }
    }

    private boolean sendNotification(String title, String content) {
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return false;
            int id = NOTIF_ID_BASE + (notifIdCounter++ % 1000);
            // 紫霞终端通知卡；BigTextStyle 保证不同 ColorOS 版本仍有完整正文兜底。
            RemoteViews compact = new RemoteViews(context.getPackageName(), R.layout.notification_hub);
            String label = "通知枢纽 // MESSAGE";
            int bg = R.drawable.notification_hub_bg;
            if (title.contains("💰") || title.contains("🎉") || title.contains("🔥") || title.contains("下注")) {
                label = "通知枢纽 // SUCCESS"; bg = R.drawable.notification_hub_success;
            } else if (title.contains("⚠") || title.contains("❌") || title.contains("异常") || title.contains("失败")) {
                label = "通知枢纽 // ALERT"; bg = R.drawable.notification_hub_alert;
            } else if (title.contains("🔮") || title.contains("新奥") || title.contains("预测") || title.contains("📥")) {
                label = title.contains("📥") ? "通知枢纽 // FILE" : "通知枢纽 // FORECAST";
                bg = R.drawable.notification_hub_forecast;
            }
            compact.setInt(R.id.hub_root, "setBackgroundResource", bg);
            compact.setTextViewText(R.id.hub_label, label);
            compact.setTextViewText(R.id.hub_title, title);
            compact.setTextViewText(R.id.hub_body, content);
            Notification notif = new Notification.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(new Notification.BigTextStyle().bigText(content))
                .setCustomContentView(compact)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setGroup(GROUP_KEY)
                .build();
            nm.notify(id, notif);

            // 发送分组摘要通知
            Notification summary = new Notification.Builder(context, CHANNEL_ID)
                .setContentTitle("面板通知")
                .setContentText(recent.size() + " 条通知")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setStyle(new Notification.InboxStyle())
                .setGroup(GROUP_KEY)
                .setGroupSummary(true)
                .setAutoCancel(false)
                .build();
            nm.notify(SUMMARY_ID, summary);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Notify: " + e.getMessage());
            return false;
        }
    }

    private String getLanIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (ni.isLoopback() || !ni.isUp()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {}
        return "unknown";
    }

    private String getJson(String json, String field) {
        if (json == null) return null;
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        idx = json.indexOf(":", idx + key.length());
        if (idx < 0) return null;
        idx++;
        while (idx < json.length() && json.charAt(idx) == ' ') idx++;
        if (idx >= json.length()) return null;
        if (json.charAt(idx) != '"') {
            StringBuilder sb = new StringBuilder();
            while (idx < json.length() && (Character.isDigit(json.charAt(idx)) || json.charAt(idx) == '-')) {
                sb.append(json.charAt(idx)); idx++;
            }
            return sb.length() > 0 ? sb.toString() : null;
        }
        idx++;
        StringBuilder sb = new StringBuilder();
        while (idx < json.length() && json.charAt(idx) != '"') {
            if (json.charAt(idx) == '\\' && idx + 1 < json.length()) {
                char n = json.charAt(idx + 1);
                sb.append(n == 'n' ? '\n' : n == 't' ? '\t' : n);
                idx += 2;
            } else { sb.append(json.charAt(idx)); idx++; }
        }
        return sb.toString();
    }

    private String esc(String s) { return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n"); }

    private static final String HTML_PAGE =
        "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
        + "<title>通知枢纽</title>"
        + "<style>*{box-sizing:border-box;margin:0;padding:0}"
        + "body{font-family:system-ui,sans-serif;color:#11213b;padding:16px;min-height:100vh;background:radial-gradient(circle at 8% 4%,#bceeff 0,transparent 30%),radial-gradient(circle at 93% 18%,#d5c4ff 0,transparent 31%),linear-gradient(145deg,#dff8ff,#c9d8f3 48%,#e7d8f5);background-attachment:fixed}"
        + ".card,.terminal{background:#ffffff52;border:1px solid #ffffffa8;backdrop-filter:blur(12px);-webkit-backdrop-filter:blur(12px);border-radius:22px;padding:18px;margin-bottom:14px;box-shadow:0 10px 28px #57719632}"
        + ".terminal{background:linear-gradient(135deg,#ffffffa6,#e9f6ff86);border-color:#ffffffd0}.bar{display:flex;justify-content:space-between;align-items:center;color:#18385e;border-bottom:1px solid #6b98bb55;padding-bottom:12px;font-size:12px}.bar b{font-size:14px;letter-spacing:.5px}.prompt{color:#2575c8;padding:12px 0 5px;font-size:13px}.cursor{animation:blink 1s step-end infinite}@keyframes blink{50%{opacity:0}}"
        + "h1{font-size:21px;color:#18385e;margin-bottom:14px;font-weight:800}h1:before{content:''}"
        + ".stat{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-top:10px}.si{background:#ffffff9c;border:1px solid #ffffffd9;padding:13px;border-radius:16px;box-shadow:inset 0 1px #fff}"
        + ".sl{font-size:12px;color:#68809b}.sv{font-size:25px;font-weight:800;color:#142a49;word-break:break-all}.btn{background:linear-gradient(135deg,#188cf0,#4f9cff);color:#fff;border:1px solid #ffffffa0;padding:11px 15px;border-radius:13px;cursor:pointer;margin:4px 2px 4px 0;font-weight:750;box-shadow:0 5px 12px #2776bd42}.btn:active{transform:scale(.97);filter:brightness(.9)}"
        + "input{background:#ffffffe6;border:1px solid #ffffff;color:#18385e;padding:12px;border-radius:13px;width:100%;margin:5px 0;font-size:16px;box-shadow:inset 0 1px 3px #54708b22}.ni{background:#ffffffa8;padding:11px;border-radius:13px;margin-bottom:7px;border-left:4px solid #2d9df4;font-size:13px;white-space:pre-wrap;color:#233b59}.logrow{display:flex;gap:7px;align-items:stretch}.logrow .ni{flex:1}.del{border:0;background:#d95a70;color:white;border-radius:12px;min-width:42px;font-size:20px;margin-bottom:7px}.ip{font-size:13px;color:#4c6682;margin-top:10px;word-break:break-all}.ok{color:#148655;font-weight:800}.wait{color:#ae7411;font-weight:800}"
        + "</style></head><body>"
        + "<div class=terminal><div class=bar><span>● ● ●</span><b> NOTIFY-HUB // LOCAL CONSOLE</b><span id=status class=wait>● CONNECTING</span></div>"
        + "<div class=prompt>$ service.status <span class=cursor>_</span></div>"
        + "<div class=stat><div class=si><div class=sl>HTTP_PORT</div><div class=sv id=port>9531</div></div>"
        + "<div class=si><div class=sl>NOTIFICATIONS</div><div class=sv id=total>-</div></div></div>"
        + "<div class=ip id=ipbox>[ LAN ] initializing…</div></div>"
        + "<div class=card><h1>🎨 外观背景</h1>"
        + "<button class=btn onclick=theme('glass')>玻璃蓝</button><button class=btn onclick=theme('terminal')>终端深色</button><button class=btn onclick=theme('purple')>紫霞</button>"
        + "<input id=bg placeholder=背景图片URL（留空使用主题背景）><button class=btn onclick=saveBg()>保存URL背景</button>"
        + "<button class=btn onclick=NativeHub.pickBackground()>从相册选择</button><button class=btn onclick=clearLocalBg()>清除本地图片</button>"
        + "<div id=bgresult class=ip></div></div>"
        + "<div class=card><h1>⚙️ 修改端口</h1>"
        + "<input id=newport type=number value=9531 placeholder=端口号>"
        + "<button class=btn onclick=chport()>修改端口</button>"
        + "<div id=portresult style=margin-top:8px;font-size:13px></div></div>"
        + "<div class=card><h1>✉️ 本机通知</h1>"
        + "<input id=t value=测试><input id=b value=内容>"
        + "<button class=btn onclick=sendOne()>发送</button>"
        + "<button class=btn onclick=tbatch()>批量测试3条</button>"
        + "<div id=r style=margin-top:8px;font-size:13px></div></div>"
        + "<div class=card><h1>📱 局域网设备</h1>"
        + "<input id=lanKey placeholder=配对密钥（所有手机填相同内容）>"
        + "<button class=btn onclick=saveKey()>保存并发现</button><button class=btn onclick=sendPeers()>群发文字</button>"
        + "<button class=btn onclick=NativeHub.pickFile()>选择图片/文件群发</button>"
        + "<div class=ip>文件仅发送给已配对在线设备｜单文件最大 20MB｜接收端不会自动执行</div><div id=fileResult class=ip></div>"
        + "<div id=peers style=margin-top:8px;font-size:13px></div></div>"
        + "<div class=card><h1>📋 通知历史</h1><div class=ip>点击内容复制｜右侧 × 删除｜最多保留 200 条</div><button class=btn onclick=clearRecent()>清空历史</button><div id=list></div></div>"
        + "<script>"
        + "const themes={glass:'radial-gradient(circle at 8% 4%,#bceeff 0,transparent 30%),radial-gradient(circle at 93% 18%,#d5c4ff 0,transparent 31%),linear-gradient(145deg,#dff8ff,#c9d8f3 48%,#e7d8f5)',terminal:'linear-gradient(145deg,#06111d,#0b2540 55%,#06121d)',purple:'radial-gradient(circle at 15% 5%,#e9c8ff 0,transparent 30%),linear-gradient(145deg,#e9ddff,#b8b7e9)'};"
        + "function paint(t,b){document.body.style.backgroundImage=b?'linear-gradient(#ffffff55,#ffffff55),url('+b+')':themes[t]||themes.glass;document.body.style.backgroundSize=b?'cover':'auto';document.body.style.backgroundPosition='center';}function setLocalBg(b){if(b)paint('glass',b);}function clearLocalBg(){NativeHub.clearLocalBackground();paint('glass','');}"
        + "async function ap(){try{const r=await fetch('/appearance');const d=await r.json();document.getElementById('bg').value=d.background||'';paint(d.theme,d.background);}catch(e){}}"
        + "async function theme(t){await fetch('/appearance',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({theme:t,background:''})});paint(t,'');document.getElementById('bg').value='';}"
        + "async function saveBg(){const b=document.getElementById('bg').value.trim();const r=await fetch('/appearance',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({theme:'glass',background:b})});const d=await r.json();document.getElementById('bgresult').textContent=d.ok?'✅背景已保存':'❌'+d.error;if(d.ok)paint('glass',b);}"
        + "async function l(){try{const r=await fetch('/health',{cache:'no-store'});const d=await r.json();document.getElementById('total').textContent=d.recent_count;document.getElementById('port').textContent=d.port;document.getElementById('ipbox').textContent='局域网地址: http://'+d.lan_ip+':'+d.port+'/';document.getElementById('status').className='ok';document.getElementById('status').textContent='● ONLINE';}catch(e){document.getElementById('status').className='wait';document.getElementById('status').textContent='● RETRYING';document.getElementById('ipbox').textContent='服务启动中，正在自动重试…';}}"
        + "function cp(x){try{if(window.NativeHub&&NativeHub.copyText){NativeHub.copyText(x);}else{const a=document.createElement('textarea');a.value=x;document.body.appendChild(a);a.select();document.execCommand('copy');a.remove();}document.getElementById('r').textContent='✅已复制完整内容';}catch(e){document.getElementById('r').textContent='❌复制失败';}}"
        + "async function delRecent(i){await fetch('/recent/delete',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({index:i})});lr();l();}async function clearRecent(){if(confirm('确认清空通知枢纽历史？此操作不可恢复')){await fetch('/recent/clear',{method:'POST'});lr();l();}}"
        + "async function lr(){try{const r=await fetch('/recent');const d=await r.json();const box=document.getElementById('list');box.innerHTML='';d.notifications.slice(0,200).forEach((n,i)=>{const row=document.createElement('div');row.className='logrow';const el=document.createElement('div');el.className='ni';el.textContent=n;el.onclick=()=>cp(n);const b=document.createElement('button');b.className='del';b.textContent='×';b.onclick=()=>delRecent(i);row.appendChild(el);row.appendChild(b);box.appendChild(row);});}catch(e){}}"
        + "async function peers(){try{const r=await fetch('/peers');const d=await r.json();document.getElementById('peers').textContent=d.peers.length?'在线 '+d.peers.length+' 台：'+d.peers.map(x=>x.name+' ('+x.ip+':'+x.port+')').join('、'):'暂未发现配对手机';}catch(e){}}"
        + "function fileResult(x){document.getElementById('fileResult').textContent=x;}"
        + "async function saveKey(){const k=document.getElementById('lanKey').value.trim();if(k.length<4){document.getElementById('peers').textContent='密钥至少4位';return}await fetch('/lan/key',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({key:k})});document.getElementById('peers').textContent='✅已保存，等待发现其他手机';}"
        + "async function sendPeers(){const k=document.getElementById('lanKey').value.trim();const t=document.getElementById('t').value,b=document.getElementById('b').value;if(!k){document.getElementById('peers').textContent='请先填配对密钥';return}const r=await fetch('/broadcast',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({title:t,body:b,relay_key:k})});const d=await r.json();document.getElementById('peers').textContent=d.ok?'✅已发送给 '+d.sent+' 台手机':'❌'+d.error;}"
        + "async function sendOne(){const t=document.getElementById('t').value;const b=document.getElementById('b').value;document.getElementById('r').textContent='发送中...';try{const r=await fetch('/',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({title:t,body:b})});const d=await r.json();document.getElementById('r').innerHTML=d.ok?'<span style=color:#22c55e>✅已发送</span>':'<span style=color:#ef4444>❌失败</span>';l();lr();}catch(e){document.getElementById('r').innerHTML='<span style=color:#ef4444>❌'+e+'</span>';}}"
        + "async function tbatch(){document.getElementById('r').textContent='批量发送中...';try{const arr=[{title:'批量1',body:'第一条通知'},{title:'批量2',body:'第二条通知'},{title:'批量3',body:'第三条通知'}];const r=await fetch('/',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({notifications:arr})});const d=await r.json();document.getElementById('r').innerHTML='<span style=color:#22c55e>✅发送'+d.sent+'条 失败'+d.failed+'条</span>';l();lr();}catch(e){document.getElementById('r').innerHTML='<span style=color:#ef4444>❌'+e+'</span>';}}"
        + "async function chport(){const p=document.getElementById('newport').value;document.getElementById('portresult').textContent='修改中...';try{const r=await fetch('/port',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({port:parseInt(p)})});const d=await r.json();if(d.ok){document.getElementById('portresult').innerHTML='<span style=color:#22c55e>✅端口已改为'+d.new_port+' 请刷新</span>';setTimeout(()=>location.reload(),2000);}else{document.getElementById('portresult').innerHTML='<span style=color:#ef4444>❌'+(d.error||'失败')+'</span>';}}catch(e){document.getElementById('portresult').innerHTML='<span style=color:#ef4444>❌'+e+'</span>';}}"
        + "ap();l();lr();peers();setInterval(()=>{l();lr();peers();},3000);"
        + "</script></body></html>";
}
