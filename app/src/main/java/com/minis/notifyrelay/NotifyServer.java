package com.minis.notifyrelay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

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
                while (recent.size() > 50) recent.remove(recent.size() - 1);
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
        while (recent.size() > 50) recent.remove(recent.size() - 1);
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

            if ("GET".equals(method) && path.equals("/peers")) {
                response = "{\"ok\":true,\"peers\":" + (lan == null ? "[]" : lan.peersJson()) + "}";
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
            Notification notif = new Notification.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(new Notification.BigTextStyle().bigText(content))
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
        + "<title>通知中转</title>"
        + "<style>*{box-sizing:border-box;margin:0;padding:0}"
        + "body{font-family:system-ui,sans-serif;background:#0f172a;color:#e2e8f0;padding:16px}"
        + ".card{background:#1e293b;border-radius:12px;padding:16px;margin-bottom:12px}"
        + "h1{font-size:18px;color:#38bdf8;margin-bottom:8px}"
        + ".stat{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-top:8px}"
        + ".si{background:#0f172a;padding:8px;border-radius:8px}"
        + ".sl{font-size:11px;color:#64748b}.sv{font-size:14px;font-weight:bold;word-break:break-all}"
        + ".btn{background:#3b82f6;color:#fff;border:none;padding:8px 16px;border-radius:8px;cursor:pointer;margin:4px 0}"
        + "input{background:#0f172a;border:1px solid #334155;color:#e2e8f0;padding:8px;border-radius:8px;width:100%;margin:4px 0}"
        + ".ni{background:#0f172a;padding:8px;border-radius:8px;margin-bottom:4px;border-left:3px solid #22c55e;font-size:12px}"
        + ".ip{font-size:12px;color:#64748b;margin-top:8px;word-break:break-all}"
        + "</style></head><body>"
        + "<div class=card><h1>📡 通知中转</h1>"
        + "<div class=stat><div class=si><div class=sl>端口</div><div class=sv id=port>9531</div></div>"
        + "<div class=si><div class=sl>通知数</div><div class=sv id=total>-</div></div></div>"
        + "<div class=ip id=ipbox>局域网: 加载中...</div></div>"
        + "<div class=card><h1>⚙️ 修改端口</h1>"
        + "<input id=newport type=number value=9531 placeholder=端口号>"
        + "<button class=btn onclick=chport()>修改端口</button>"
        + "<div id=portresult style=margin-top:8px;font-size:13px></div></div>"
        + "<div class=card><h1>✉️ 本机通知</h1>"
        + "<input id=t value=测试><input id=b value=内容>"
        + "<button class=btn onclick=sendOne()>发送</button>"
        + "<button class=btn onclick=tbatch()>批量测试3条</button>"
        + "<div id=r style=margin-top:8px;font-size:13px></div></div>"
        + "<div class=card><h1>📱 局域网手机</h1>"
        + "<input id=lanKey placeholder=配对密钥（所有手机填相同内容）>"
        + "<button class=btn onclick=saveKey()>保存并发现</button><button class=btn onclick=sendPeers()>发给全部在线手机</button>"
        + "<div id=peers style=margin-top:8px;font-size:13px></div></div>"
        + "<div class=card><h1>📋 最近（点击复制）</h1><div id=list></div></div>"
        + "<script>"
        + "async function l(){try{const r=await fetch('/health');const d=await r.json();document.getElementById('total').textContent=d.recent_count;document.getElementById('port').textContent=d.port;document.getElementById('ipbox').textContent='局域网地址: http://'+d.lan_ip+':'+d.port+'/';}catch(e){}}"
        + "async function cp(x){try{await navigator.clipboard.writeText(x);document.getElementById('r').textContent='✅已复制';}catch(e){const a=document.createElement('textarea');a.value=x;document.body.appendChild(a);a.select();document.execCommand('copy');a.remove();document.getElementById('r').textContent='✅已复制';}}"
        + "async function lr(){try{const r=await fetch('/recent');const d=await r.json();document.getElementById('list').innerHTML=d.notifications.slice(0,20).map(n=>'<div class=ni onclick=cp(this.dataset.x) data-x='+JSON.stringify(n)+'>'+n+'</div>').join('');}catch(e){}}"
        + "async function peers(){try{const r=await fetch('/peers');const d=await r.json();document.getElementById('peers').textContent=d.peers.length?'在线 '+d.peers.length+' 台：'+d.peers.map(x=>x.name+' ('+x.ip+':'+x.port+')').join('、'):'暂未发现配对手机';}catch(e){}}"
        + "async function saveKey(){const k=document.getElementById('lanKey').value.trim();if(k.length<4){document.getElementById('peers').textContent='密钥至少4位';return}await fetch('/lan/key',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({key:k})});document.getElementById('peers').textContent='✅已保存，等待发现其他手机';}}"
        + "async function sendPeers(){const k=document.getElementById('lanKey').value.trim();const t=document.getElementById('t').value,b=document.getElementById('b').value;if(!k){document.getElementById('peers').textContent='请先填配对密钥';return}const r=await fetch('/broadcast',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({title:t,body:b,relay_key:k})});const d=await r.json();document.getElementById('peers').textContent=d.ok?'✅已发送给 '+d.sent+' 台手机':'❌'+d.error;}}"
        + "async function sendOne(){const t=document.getElementById('t').value;const b=document.getElementById('b').value;document.getElementById('r').textContent='发送中...';try{const r=await fetch('/',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({title:t,body:b})});const d=await r.json();document.getElementById('r').innerHTML=d.ok?'<span style=color:#22c55e>✅已发送</span>':'<span style=color:#ef4444>❌失败</span>';l();lr();}catch(e){document.getElementById('r').innerHTML='<span style=color:#ef4444>❌'+e+'</span>';}}"
        + "async function tbatch(){document.getElementById('r').textContent='批量发送中...';try{const arr=[{title:'批量1',body:'第一条通知'},{title:'批量2',body:'第二条通知'},{title:'批量3',body:'第三条通知'}];const r=await fetch('/',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({notifications:arr})});const d=await r.json();document.getElementById('r').innerHTML='<span style=color:#22c55e>✅发送'+d.sent+'条 失败'+d.failed+'条</span>';l();lr();}catch(e){document.getElementById('r').innerHTML='<span style=color:#ef4444>❌'+e+'</span>';}}"
        + "async function chport(){const p=document.getElementById('newport').value;document.getElementById('portresult').textContent='修改中...';try{const r=await fetch('/port',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({port:parseInt(p)})});const d=await r.json();if(d.ok){document.getElementById('portresult').innerHTML='<span style=color:#22c55e>✅端口已改为'+d.new_port+' 请刷新</span>';setTimeout(()=>location.reload(),2000);}else{document.getElementById('portresult').innerHTML='<span style=color:#ef4444>❌'+(d.error||'失败')+'</span>';}}catch(e){document.getElementById('portresult').innerHTML='<span style=color:#ef4444>❌'+e+'</span>';}}"
        + "l();lr();setInterval(()=>{l();lr();},5000);"
        + "</script></body></html>";
}
