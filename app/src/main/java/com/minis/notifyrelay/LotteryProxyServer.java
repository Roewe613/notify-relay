package com.minis.notifyrelay;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;

/** 本地开奖代理：9533。通知9531完全不经过这里。 */
public class LotteryProxyServer {
    private static LotteryProxyServer shared;
    private final Context ctx;
    private volatile boolean running;
    private ServerSocket listener;
    private ExecutorService pool = Executors.newCachedThreadPool();
    private LotteryProxyServer(Context c) { ctx = c.getApplicationContext(); }
    public static synchronized LotteryProxyServer get(Context c) { if (shared == null) shared = new LotteryProxyServer(c); return shared; }
    public synchronized void start() {
        if (running) return; running = true;
        new Thread(() -> { try {
            listener = new ServerSocket(9533, 32, InetAddress.getByName("127.0.0.1"));
            while (running) { final Socket s = listener.accept(); pool.execute(() -> handle(s)); }
        } catch (Exception ignored) {} finally { running = false; } }, "lottery-proxy-9533").start();
    }
    public boolean isRunning() { return running && listener != null && !listener.isClosed(); }
    private void handle(Socket client) {
        try (Socket c = client) {
            c.setSoTimeout(15000);
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "ISO-8859-1"));
            String first = r.readLine(); if (first == null) return;
            String line; java.util.List<String> headers = new java.util.ArrayList<>();
            while ((line = r.readLine()) != null && line.length() > 0) headers.add(line);
            String[] p = first.split(" ", 3); if (p.length < 2) return;
            String target = p[1]; URI uri = target.startsWith("http") ? new URI(target) : new URI("https://" + target);
            String host = uri.getHost(); int destPort = uri.getPort() > 0 ? uri.getPort() : ("CONNECT".equalsIgnoreCase(p[0]) ? 443 : 80);
            SharedPreferences sp = ctx.getSharedPreferences("notify_relay", Context.MODE_PRIVATE);
            String ip = sp.getString("lottery_proxy_ip", "").trim(); int port = sp.getInt("lottery_proxy_port", 0);
            String phost = sp.getString("lottery_proxy_host", "").trim(); String auth = sp.getString("lottery_proxy_auth", "");
            if (ip.length() == 0 || port < 1) { write(c, "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n"); return; }
            try (Socket up = new Socket()) {
                up.connect(new InetSocketAddress(ip, port), 8000); up.setSoTimeout(15000);
                OutputStream out = up.getOutputStream(); InputStream in = up.getInputStream();
                if ("CONNECT".equalsIgnoreCase(p[0])) {
                    out.write(("CONNECT " + host + ":" + destPort + " HTTP/1.1\r\nHost: " + (phost.isEmpty()?host:phost) + "\r\n" + (auth.isEmpty()?"":"X-T5-Auth: "+auth+"\r\n") + "Connection: keep-alive\r\n\r\n").getBytes("ISO-8859-1")); out.flush();
                    BufferedReader ur = new BufferedReader(new InputStreamReader(in, "ISO-8859-1")); String status = ur.readLine();
                    while ((line=ur.readLine()) != null && line.length()>0) {}
                    if (status == null || !status.contains(" 200 ")) { write(c, "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n"); return; }
                    write(c, "HTTP/1.1 200 Connection Established\r\n\r\n"); tunnel(c, up);
                } else {
                    String path = uri.getRawPath(); if (path == null || path.isEmpty()) path = "/"; if (uri.getRawQuery()!=null) path += "?"+uri.getRawQuery();
                    StringBuilder q = new StringBuilder(p[0]+" "+path+" HTTP/1.1\r\nHost: "+host+"\r\n");
                    for (String h: headers) if (!h.toLowerCase().startsWith("proxy-connection") && !h.toLowerCase().startsWith("host:")) q.append(h).append("\r\n");
                    if (!auth.isEmpty()) q.append("X-T5-Auth: ").append(auth).append("\r\n"); q.append("Connection: close\r\n\r\n"); out.write(q.toString().getBytes("ISO-8859-1")); out.flush(); copy(in,c.getOutputStream());
                }
            }
        } catch (Exception ignored) {}
    }
    private void tunnel(Socket a, Socket b) throws IOException { ExecutorService x=Executors.newFixedThreadPool(2); x.submit(()->{try{copy(a.getInputStream(),b.getOutputStream());}catch(Exception e){}}); copy(b.getInputStream(),a.getOutputStream()); x.shutdownNow(); }
    private void copy(InputStream in, OutputStream out) throws IOException { byte[] buf=new byte[8192]; int n; while((n=in.read(buf))>=0){out.write(buf,0,n);out.flush();} }
    private void write(Socket s,String x)throws IOException{s.getOutputStream().write(x.getBytes("ISO-8859-1"));s.getOutputStream().flush();}
}
