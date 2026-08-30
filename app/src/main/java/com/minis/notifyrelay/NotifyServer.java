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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NotifyServer {
    private static final String TAG = "NotifyRelay";
    private static NotifyServer shared;

    /** 单例只创建；9531 只能由前台 Service 显式启动，Activity 不参与监听生命周期。 */
    public static synchronized NotifyServer getShared(Context ctx) {
        if (shared == null) shared = new NotifyServer(ctx.getApplicationContext());
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
    private final ExecutorService clientPool = Executors.newFixedThreadPool(4);
    private volatile long requestCount = 0, successCount = 0, failureCount = 0;
    private volatile String lastError = "";
    private volatile long lastRequestTime = 0;
    private volatile boolean running = false;
    private volatile boolean watchdogStarted = false;
    private volatile int serverGeneration = 0;
    private final List<String> recent = Collections.synchronizedList(new ArrayList<>());
    private long startTime;
    private int port = 9531;
    private LanPeerManager lan;
    private FileTransferServer fileTransfer;

    public NotifyServer(Context ctx) {
        context = ctx;
        SharedPreferences prefs = ctx.getSharedPreferences("notify_relay", Context.MODE_PRIVATE);
        port = prefs.getInt("port", 9531);
        ensureChannel();
        loadRecent(prefs);
    }

    public int getPort() { return port; }
    public boolean isRunning() { return running; }
    public synchronized boolean isLanEnabled() { return lan != null; }
    public synchronized boolean isFileEnabled() { return fileTransfer != null; }
    public synchronized void setLanEnabled(boolean enabled) {
        if (enabled && lan == null) {
            SharedPreferences prefs = context.getSharedPreferences("notify_relay", Context.MODE_PRIVATE);
            String key = prefs.getString("lan_key", "");
            if (key.length() < 4) { key = UUID.randomUUID().toString().replace("-", "").substring(0,12); prefs.edit().putString("lan_key", key).commit(); }
            lan = new LanPeerManager(context, port, key); lan.start();
        } else if (!enabled && lan != null) { lan.stop(); lan = null; if (fileTransfer != null) { fileTransfer.stop(); fileTransfer = null; } }
    }
    public synchronized void setFileEnabled(boolean enabled) {
        if (enabled) { if (lan == null) setLanEnabled(true); if (fileTransfer == null) { fileTransfer = new FileTransferServer(context, port + 3, ""); fileTransfer.start(); } }
        else if (fileTransfer != null) { fileTransfer.stop(); fileTransfer = null; }
    }
    public int broadcastFile(File file, String mime) { return (lan == null || fileTransfer == null) ? 0 : lan.broadcastFile(file, mime); }

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

    /**
     * 核心通知监听器：单线程、固定9531、没有自动换端口/自动重建。
     * 局域网发现和大文件传输不参与此处启动，避免影响面板通知主链路。
     */
    public synchronized void start() {
        if (running) return;
        running = true;
        startTime = System.currentTimeMillis();
        serverThread = new Thread(() -> {
            try {
                ServerSocket socket = new ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"));
                synchronized (NotifyServer.this) { serverSocket = socket; }
                Log.i(TAG, "Stable HTTP notification server on 0.0.0.0:" + port);
                while (running && !socket.isClosed()) {
                    try {
                        Socket client = socket.accept();
                        client.setSoTimeout(8000);
                        clientPool.execute(() -> handleClient(client));
                    } catch (IOException e) {
                        if (running) Log.w(TAG, "Accept: " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Cannot bind fixed notification port " + port + ": " + e.getMessage());
            } finally {
                synchronized (NotifyServer.this) { serverSocket = null; running = false; }
            }
        }, "hub-http-server");
        serverThread.start();
    }

    private synchronized void startWatchdog() {
        if (watchdogStarted) return;
        watchdogStarted = true;
        new Thread(() -> {
            while (true) {
                try { Thread.sleep(30000); } catch (InterruptedException e) { return; }
                if (running && !isResponsive()) {
                    Log.w(TAG, "HTTP server unresponsive; restarting listener");
                    synchronized (NotifyServer.this) { stop(); start(); }
                }
            }
        }, "hub-server-watchdog").start();
    }

    private boolean isResponsive() {
        try (Socket s = new Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 2500);
            s.setSoTimeout(2500);
            OutputStream out = s.getOutputStream();
            out.write("GET /health HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".getBytes("UTF-8")); out.flush();
            return s.getInputStream().read() > 0;
        } catch (Exception e) { return false; }
    }

    /** 每次App显式打开时强制回收旧监听，解决端口假存活/半死连接。 */
    public synchronized void forceRestart() {
        stop();
        try { Thread.sleep(120); } catch (InterruptedException ignored) { }
        start();
    }

    public synchronized void stop() {
        running = false;
        serverGeneration++;
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException e) { }
            serverSocket = null;
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
        requestCount++;
        lastRequestTime = System.currentTimeMillis();
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

            if ("GET".equals(method) && path.equals("/lottery/status")) {
                LotteryBridge bridge = LotteryBridge.get();
                response = bridge == null ? "{\"ok\":false,\"error\":\"activity not ready\"}" : bridge.statusJson();
            } else if ("GET".equals(method) && (path.equals("/lottery/TJSSC") || path.equals("/lottery/XJSSC"))) {
                LotteryBridge bridge = LotteryBridge.get();
                String code = path.endsWith("TJSSC") ? "TJSSC" : "XJSSC";
                response = "{\"ok\":" + (bridge != null) + ",\"code\":\"" + code + "\",\"rows\":" + (bridge == null ? "[]" : bridge.getRows(code)) + "}";
            } else if ("POST".equals(method) && path.equals("/lottery/sync")) {
                LotteryBridge bridge = LotteryBridge.get();
                response = "{\"ok\":" + (bridge != null && bridge.requestSync()) + ",\"started\":" + (bridge != null) + "}";
            } else if ("POST".equals(method) && path.equals("/lottery/config")) {
                try {
                    LotteryBridge bridge = LotteryBridge.get(); JSONObject json = new JSONObject(body);
                    boolean ok = bridge != null && bridge.setConfig(json);
                    response = "{\"ok\":" + ok + ",\"status\":" + (bridge == null ? "{}" : bridge.statusJson()) + "}";
                } catch (Exception e) { response = "{\"ok\":false,\"error\":\"invalid lottery config\"}"; }
            } else if ("GET".equals(method) && (path.equals("/lottery/diag/TJSSC") || path.equals("/lottery/diag/XJSSC"))) {
                LotteryBridge bridge = LotteryBridge.get(); String code = path.endsWith("TJSSC") ? "TJSSC" : "XJSSC";
                response = bridge == null ? "{\"ok\":false,\"error\":\"activity not ready\"}" : bridge.diagnosticsJson(code);
            } else if ("GET".equals(method) && path.equals("/lottery")) {
                response = LOTTERY_PAGE; contentType = "text/html; charset=utf-8";
            } else if ("GET".equals(method) && path.equals("/modules")) {
                response = "{\"lan\":" + isLanEnabled() + ",\"file\":" + isFileEnabled() + ",\"file_port\":" + (port + 3) + "}";
            } else if ("POST".equals(method) && path.equals("/modules")) {
                try {
                    JSONObject json = new JSONObject(body);
                    String name = json.optString("name", ""); boolean enabled = json.optBoolean("enabled", false);
                    if ("lan".equals(name)) setLanEnabled(enabled);
                    else if ("file".equals(name)) setFileEnabled(enabled);
                    else throw new Exception("unknown");
                    response = "{\"ok\":true,\"lan\":" + isLanEnabled() + ",\"file\":" + isFileEnabled() + "}";
                } catch (Exception e) { response = "{\"ok\":false,\"error\":\"invalid module\"}"; }
            } else if ("POST".equals(method) && path.equals("/file")) {
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
                response = "{\"ok\":true,\"remaining\":0}";
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
                    response = "{\"status\":\"ok\",\"service\":\"notify-relay\",\"port\":" + port + ",\"recent_count\":" + recent.size() + ",\"uptime\":" + uptime + ",\"lan_ip\":\"" + getLanIp() + "\",\"requests\":" + requestCount + ",\"success\":" + successCount + ",\"failed\":" + failureCount + ",\"last_request\":" + lastRequestTime + ",\"last_error\":\"" + esc(lastError) + "\"}";
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
            successCount++;
        } catch (Exception e) {
            failureCount++;
            lastError = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
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
            boolean forecast = title.contains("🔮") || title.contains("新奥") || title.contains("预测");
            if (title.contains("💰") || title.contains("🎉") || title.contains("🔥") || title.contains("下注")) {
                label = "通知枢纽 // SUCCESS"; bg = R.drawable.notification_hub_success;
            } else if (title.contains("⚠") || title.contains("❌") || title.contains("异常") || title.contains("失败")) {
                label = "通知枢纽 // ALERT"; bg = R.drawable.notification_hub_alert;
            } else if (title.contains("🔮") || title.contains("新奥") || title.contains("预测") || title.contains("📥")) {
                label = title.contains("📥") ? "通知枢纽 // FILE" : "通知枢纽 // FORECAST";
                bg = R.drawable.notification_hub_forecast;
            }
            // Root保持透明，直接融入SystemUI全局浅薰衣草卡片，不再产生第二层颜色块。
            if (forecast && content.startsWith("重点：")) {
                // 分档长号码：去掉装饰栏，把重点码放标题，正文三行专供完整号码。
                int cut = content.indexOf('\n');
                String focus = cut > 0 ? content.substring(0, cut).replace("重点：", "重点 ") : content.replace("重点：", "重点 ");
                String numbers = cut > 0 ? content.substring(cut + 1) : "";
                compact.setViewVisibility(R.id.hub_mark, android.view.View.GONE);
                compact.setViewVisibility(R.id.hub_label, android.view.View.GONE);
                compact.setViewVisibility(R.id.hub_status, android.view.View.GONE);
                compact.setTextViewText(R.id.hub_title, title.replace("新奥 ", "") + "｜" + focus);
                compact.setTextViewText(R.id.hub_body, numbers);
            } else {
                compact.setTextViewText(R.id.hub_label, label);
                compact.setTextViewText(R.id.hub_title, title);
                compact.setTextViewText(R.id.hub_body, content);
            }
            // 开奖验证标题包含彩种名和期号，单独缩小避免通知卡换行；其他通知保持原字号。
            if (title.contains("开奖验证") || title.contains("验证 期") || title.contains("不定位验证")) {
                compact.setTextViewTextSize(R.id.hub_title, android.util.TypedValue.COMPLEX_UNIT_SP, 10f);
                compact.setTextViewTextSize(R.id.hub_body, android.util.TypedValue.COMPLEX_UNIT_SP, 8f);
                compact.setInt(R.id.hub_body, "setMaxLines", 4);
            }
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

    private static final String LOTTERY_PAGE = "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><meta charset=utf-8><title>开奖代理转发</title>"
        + "<style>body{font-family:system-ui,sans-serif;background:#08101f;color:#e8f1ff;margin:0;padding:62px 16px 16px}.top{position:fixed;top:0;left:0;right:0;z-index:10;background:#08101fee;padding:10px 16px;border-bottom:1px solid #29405f}.card{background:#121d31;border:1px solid #29405f;border-radius:16px;padding:14px;margin:12px 0}input{box-sizing:border-box;width:100%;padding:10px;margin:6px 0;border-radius:9px;border:1px solid #456;background:#091321;color:#fff}button{padding:10px 13px;margin:5px;border:0;border-radius:9px;background:#168cff;color:#fff}.row{display:grid;grid-template-columns:1fr 1fr;gap:8px}.muted{color:#9eb0c8;font-size:12px}pre{white-space:pre-wrap;word-break:break-all;font-size:12px}.ok{color:#45d483}.warn{color:#ffc857}</style>"
        + "<div class=top><button onclick=location.href='/' style='padding:8px 12px;border:0;border-radius:9px;background:#168cff;color:#fff'>← 通知枢纽首页</button></div><h2>🎲 开奖代理转发</h2><div id=state class=card>加载中…</div>"
        + "<div class=card><h3>开奖网址</h3><label>天津网址<input id=tj></label><label>新疆网址<input id=xj></label></div>"
        + "<div class=card><h3>同步设置</h3><div class=row><label>兜底间隔（分钟）<input id=min type=number min=1 max=60></label><label>获取期数<input id=count type=number min=1 max=20></label></div><label>开奖后触发延迟（30～60秒）<input id=delay type=number min=30 max=60 value=30></label><div class=muted>精准时间：天津每小时03/23/43分40秒；新疆每小时00/20/40分约07秒。默认开奖后30秒同步，兜底间隔仅用于异常恢复。</div><button onclick=save()>保存设置</button><button onclick=sync()>立即刷新</button><button onclick=load()>重新载入</button></div>"
        + "<div class=card><h3>百度代理转发（本地9533）</h3><label>百度代理 IP<input id=pi placeholder='例如 1.2.3.4'></label><label>代理端口<input id=pp type=number placeholder='例如 8080'></label><label>Host<input id=ph placeholder='代理要求的Host，可留空'></label><label>X-T5-Auth<input id=pa type=password placeholder='认证值'></label><div class=muted>9531 仅通知；9533 仅开奖代理。HTTPS通过本地代理转发，保留目标域名/SNI，不直接把IP填进WebView。</div><button onclick=save()>保存代理</button></div>"
        + "<div class=card><h3>连通性测试</h3><button onclick=diag('TJSSC')>天津 DNS解析 / TCP/HTTPS</button><button onclick=diag('XJSSC')>新疆 DNS解析 / TCP/HTTPS</button><pre id=tjd></pre><pre id=xjd></pre></div>"
        + "<div class=card><h3>当前同步状态</h3><pre id=tjr></pre><pre id=xjr></pre></div>"
        + "<script>async function j(u,o){return await (await fetch(u,o)).json()}function fmt(r){return (r.rows||[]).map(x=>x.issue+'  '+x.openNum.join(' ')+'  '+x.openDateTime).join('\\n')||'暂无缓存'}async function load(){let s=await j('/lottery/status');state.innerHTML='代理状态：'+(s.proxy_running?'<b class=ok>9533运行中</b>':'<b class=warn>9533未运行</b>')+'｜同步：'+(s.syncing?'同步中':'空闲')+'｜最近：'+(s.last_sync?new Date(s.last_sync).toLocaleString():'无')+'<br>错误：'+(s.last_error||'无');tj.value=s.tj_url;xj.value=s.xj_url;min.value=s.interval_min;count.value=s.count;delay.value=s.trigger_delay_sec||30;pi.value=s.proxy_ip||'';pp.value=s.proxy_port||'';ph.value=s.proxy_host||'';pa.value='';document.getElementById('state').innerHTML+='｜认证：'+(s.proxy_auth_configured?'<b class=ok>已配置</b>':'<b class=warn>未配置</b>');tjr.textContent='天津缓存：\\n'+fmt(await j('/lottery/TJSSC'));xjr.textContent='新疆缓存：\\n'+fmt(await j('/lottery/XJSSC'))}async function save(){let d={tj_url:tj.value,xj_url:xj.value,interval_min:+min.value,count:+count.value,trigger_delay_sec:+delay.value,proxy_ip:pi.value,proxy_port:+pp.value||0,proxy_host:ph.value};if(pa.value.trim())d.proxy_auth=pa.value.trim();let r=await j('/lottery/config',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(d)});state.textContent=r.ok?'✅设置已保存':'❌保存失败';load()}async function sync(){state.textContent='⏳正在同步…';await j('/lottery/sync',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'});setTimeout(load,7000)}async function diag(c){let e=document.getElementById(c==='TJSSC'?'tjd':'xjd');e.textContent='测试中…';e.textContent=JSON.stringify(await j('/lottery/diag/'+c),null,2)}load();setInterval(load,10000)</script>";

    private static final String HTML_PAGE =
        "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
        + "<title>通知枢纽</title>"
        + "<style>*{box-sizing:border-box;margin:0;padding:0}"
        + "body{font-family:system-ui,sans-serif;color:#11213b;padding:16px;min-height:100vh;background:radial-gradient(circle at 15% 5%,#e9c8ff 0,transparent 30%),linear-gradient(145deg,#e9ddff,#b8b7e9);background-attachment:fixed}"
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
        + "<div class=card><h1>⚙️ 修改端口</h1>"
        + "<input id=newport type=number value=9531 placeholder=端口号>"
        + "<button class=btn onclick=chport()>修改端口</button>"
        + "<div id=portresult style=margin-top:8px;font-size:13px></div></div>"
        + "<div class=card><h1>✉️ 本机通知</h1>"
        + "<input id=t value=测试><input id=b value=内容>"
        + "<button class=btn onclick=sendOne()>发送</button>"
        + "<button class=btn onclick=tbatch()>批量测试3条</button>"
        + "<div id=r style=margin-top:8px;font-size:13px></div></div>"
        + "<div class=card><h1>🎲 开奖代理</h1><div class=ip>9533 开奖代理独立运行，不影响9531通知服务。</div><button class=btn onclick=location.href='/lottery'>打开天津·新疆开奖配置</button></div>"
        + "<div class=card><h1>📋 通知历史</h1><div class=ip>点击内容复制｜右侧 × 删除｜最多保留 200 条</div><button class=btn onclick=clearRecent()>清空历史</button><div id=list></div></div>"
        + "<script>"
        + "const themes={purple:'radial-gradient(circle at 15% 5%,#e9c8ff 0,transparent 30%),linear-gradient(145deg,#e9ddff,#b8b7e9)'};document.body.style.backgroundImage=themes.purple;"
        + "function paint(t,b){document.body.style.backgroundImage=themes.purple;}"
        + "async function l(){try{const r=await fetch('/health',{cache:'no-store'});const d=await r.json();document.getElementById('total').textContent=d.recent_count;document.getElementById('port').textContent=d.port;document.getElementById('ipbox').textContent='局域网地址: http://'+d.lan_ip+':'+d.port+'/';document.getElementById('status').className='ok';document.getElementById('status').textContent='● ONLINE';}catch(e){document.getElementById('status').className='wait';document.getElementById('status').textContent='● RETRYING';document.getElementById('ipbox').textContent='服务启动中，正在自动重试…';}}"
        + "function cp(x){try{if(window.NativeHub&&NativeHub.copyText){NativeHub.copyText(x);}else{const a=document.createElement('textarea');a.value=x;document.body.appendChild(a);a.select();document.execCommand('copy');a.remove();}document.getElementById('r').textContent='✅已复制完整内容';}catch(e){document.getElementById('r').textContent='❌复制失败';}}"
        + "async function delRecent(i){await fetch('/recent/delete',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({index:i})});lr();l();}let clearArmed=0;async function clearRecent(){const b=event.currentTarget;if(!clearArmed){clearArmed=Date.now();b.textContent='再次点击确认清空';setTimeout(()=>{if(clearArmed){clearArmed=0;b.textContent='清空历史';}},4000);return;}clearArmed=0;b.textContent='清空中…';try{const r=await fetch('/recent/clear',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'});const d=await r.json();b.textContent=d.ok?'✅已清空':'❌清空失败';document.getElementById('r').textContent=d.ok?'✅历史已清空':'❌清空失败';await lr();await l();setTimeout(()=>b.textContent='清空历史',1500);}catch(e){b.textContent='❌清空失败';}}"
        + "async function lr(){try{const r=await fetch('/recent');const d=await r.json();const box=document.getElementById('list');box.innerHTML='';d.notifications.slice(0,200).forEach((n,i)=>{const row=document.createElement('div');row.className='logrow';const el=document.createElement('div');el.className='ni';el.textContent=n;el.onclick=()=>cp(n);const b=document.createElement('button');b.className='del';b.textContent='×';b.onclick=()=>delRecent(i);row.appendChild(el);row.appendChild(b);box.appendChild(row);});}catch(e){}}"
        + "async function sendOne(){const t=document.getElementById('t').value;const b=document.getElementById('b').value;document.getElementById('r').textContent='发送中...';try{const r=await fetch('/',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({title:t,body:b})});const d=await r.json();document.getElementById('r').innerHTML=d.ok?'<span style=color:#22c55e>✅已发送</span>':'<span style=color:#ef4444>❌失败</span>';l();lr();}catch(e){document.getElementById('r').innerHTML='<span style=color:#ef4444>❌'+e+'</span>';}}"
        + "async function tbatch(){document.getElementById('r').textContent='批量发送中...';try{const arr=[{title:'批量1',body:'第一条通知'},{title:'批量2',body:'第二条通知'},{title:'批量3',body:'第三条通知'}];const r=await fetch('/',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({notifications:arr})});const d=await r.json();document.getElementById('r').innerHTML='<span style=color:#22c55e>✅发送'+d.sent+'条 失败'+d.failed+'条</span>';l();lr();}catch(e){document.getElementById('r').innerHTML='<span style=color:#ef4444>❌'+e+'</span>';}}"
        + "async function chport(){const p=document.getElementById('newport').value;document.getElementById('portresult').textContent='修改中...';try{const r=await fetch('/port',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({port:parseInt(p)})});const d=await r.json();if(d.ok){document.getElementById('portresult').innerHTML='<span style=color:#22c55e>✅端口已改为'+d.new_port+' 请刷新</span>';setTimeout(()=>location.reload(),2000);}else{document.getElementById('portresult').innerHTML='<span style=color:#ef4444>❌'+(d.error||'失败')+'</span>';}}catch(e){document.getElementById('portresult').innerHTML='<span style=color:#ef4444>❌'+e+'</span>';}}"
        + "l();lr();setInterval(()=>{l();lr();},3000);"
        + "</script></body></html>";
}
