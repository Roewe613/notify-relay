package com.minis.notifyrelay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.*;

public class NotifyRelayService extends Service {
    private static final String TAG = "NotifyRelay";
    private static final int PORT = 9530;
    private static final String CHANNEL_ID = "panel_notify";
    private static final int NOTIF_ID_BASE = 9000;
    private int notifIdCounter = 0;

    private ServerSocket serverSocket;
    private Thread serverThread;
    private volatile boolean running = false;
    private final List<String> recent = Collections.synchronizedList(new ArrayList<>());
    private long startTime;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        startTime = System.currentTimeMillis();
        createNotificationChannel();
        running = true;
        startServer();
        Log.i(TAG, "Service started on port " + PORT);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (serverSocket != null) try { serverSocket.close(); } catch (IOException e) {}
        super.onDestroy();
    }

    private void createNotificationChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel chan = new NotificationChannel(CHANNEL_ID, "面板通知", NotificationManager.IMPORTANCE_HIGH);
        chan.enableVibration(true);
        chan.enableLights(true);
        chan.setShowBadge(true);
        chan.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(chan);
    }

    private void startServer() {
        serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT, 50, InetAddress.getByName("0.0.0.0"));
                Log.i(TAG, "HTTP server on 0.0.0.0:" + PORT);
                while (running) {
                    try {
                        Socket client = serverSocket.accept();
                        new Thread(() -> handleClient(client)).start();
                    } catch (IOException e) {
                        if (running) Log.e(TAG, "Accept: " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Server: " + e.getMessage());
            }
        });
        serverThread.start();
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
            if ("POST".equals(method)) {
                String title = getJson(body, "title");
                if (title == null) title = getJson(body, "name");
                if (title == null) title = "通知";
                String content = getJson(body, "body");
                if (content == null) content = getJson(body, "content");
                if (content == null) content = getJson(body, "text");
                if (content == null) content = getJson(body, "message");
                if (content == null) content = "通知";
                if (content.length() > 200) content = content.substring(0, 200);
                boolean ok = sendNotification(title, content);
                String ts = new SimpleDateFormat("MM-dd HH:mm:ss").format(new Date());
                recent.add(0, ts + " | " + (ok ? "✅" : "❌") + " " + title + " | " + content);
                if (recent.size() > 50) recent.remove(recent.size() - 1);
                response = "{\"ok\":" + ok + "}";
            } else if ("GET".equals(method)) {
                if (path.equals("/health")) {
                    long uptime = (System.currentTimeMillis() - startTime) / 1000;
                    response = "{\"status\":\"ok\",\"service\":\"notify-relay\",\"port\":" + PORT + ",\"recent_count\":" + recent.size() + ",\"uptime\":" + uptime + "}";
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
            String header = "HTTP/1.1 200 OK\r\nContent-Type: " + contentType + "\r\nContent-Length: " + respBytes.length + "\r\nConnection: close\r\n\r\n";
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
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return false;
            Notification notif = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(new Notification.BigTextStyle().bigText(content))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build();
            nm.notify(NOTIF_ID_BASE + (notifIdCounter++ % 1000), notif);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Notify: " + e.getMessage());
            return false;
        }
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
        if (idx >= json.length() || json.charAt(idx) != '"') return null;
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
        + ".sl{font-size:11px;color:#64748b}.sv{font-size:16px;font-weight:bold}"
        + ".btn{background:#3b82f6;color:#fff;border:none;padding:8px 16px;border-radius:8px;cursor:pointer}"
        + "input{background:#0f172a;border:1px solid #334155;color:#e2e8f0;padding:8px;border-radius:8px;width:100%;margin:4px 0}"
        + ".ni{background:#0f172a;padding:8px;border-radius:8px;margin-bottom:4px;border-left:3px solid #22c55e;font-size:12px}"
        + "</style></head><body>"
        + "<div class=card><h1>📡 通知中转</h1>"
        + "<div class=stat><div class=si><div class=sl>端口</div><div class=sv>9530</div></div>"
        + "<div class=si><div class=sl>通知数</div><div class=sv id=total>-</div></div></div></div>"
        + "<div class=card><h1>✉️ 测试</h1>"
        + "<input id=t value=测试><input id=b value=内容>"
        + "<button class=btn onclick=t()>发送</button>"
        + "<div id=r style=margin-top:8px;font-size:13px></div></div>"
        + "<div class=card><h1>📋 最近</h1><div id=list></div></div>"
        + "<script>"
        + "async function l(){try{const r=await fetch('/health');const d=await r.json();document.getElementById('total').textContent=d.recent_count;}catch(e){}}"
        + "async function lr(){try{const r=await fetch('/recent');const d=await r.json();document.getElementById('list').innerHTML=d.notifications.slice(0,20).map(n=>'<div class=ni>'+n+'</div>').join('');}catch(e){}}"
        + "async function t(){const t=document.getElementById('t').value;const b=document.getElementById('b').value;document.getElementById('r').textContent='发送中...';try{const r=await fetch('/',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({title:t,body:b})});const d=await r.json();document.getElementById('r').innerHTML=d.ok?'<span style=color:#22c55e>✅已发送</span>':'<span style=color:#ef4444>❌失败</span>';l();lr();}catch(e){document.getElementById('r').innerHTML='<span style=color:#ef4444>❌'+e+'</span>';}}"
        + "l();lr();setInterval(()=>{l();lr();},5000);"
        + "</script></body></html>";
}
