package com.minis.notifyrelay;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import org.json.JSONObject;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** 局域网设备发现与通知互发。只有相同 groupKey 的设备才会互相显示。 */
public class LanPeerManager {
    private static final String TAG = "NotifyRelayLan";
    private static final int DISCOVERY_PORT = 9532;
    private final Context context;
    private final int httpPort;
    private final ConcurrentHashMap<String, Peer> peers = new ConcurrentHashMap<>();
    private volatile boolean running;
    private String groupKey;

    public static class Peer {
        public String name, ip; public int port; public long seen;
        Peer(String name, String ip, int port) { this.name=name; this.ip=ip; this.port=port; this.seen=System.currentTimeMillis(); }
    }
    public LanPeerManager(Context c, int port, String key) { context=c; httpPort=port; groupKey=key; }
    public void setGroupKey(String k) { groupKey=k; }
    public String getGroupKey() { return groupKey; }
    public void start() {
        if (running) return; running=true;
        new Thread(this::listen, "relay-lan-listen").start();
        new Thread(() -> { while(running) { announce(); clean(); try { Thread.sleep(10000); } catch(Exception e){} } }, "relay-lan-announce").start();
    }
    public void stop() { running=false; }
    private void listen() {
        try (DatagramSocket s = new DatagramSocket(DISCOVERY_PORT, InetAddress.getByName("0.0.0.0"))) {
            s.setBroadcast(true); byte[] b=new byte[1024];
            while(running) try {
                DatagramPacket p=new DatagramPacket(b,b.length); s.receive(p);
                JSONObject j=new JSONObject(new String(p.getData(),0,p.getLength(),"UTF-8"));
                if (!"minis-notify-v1".equals(j.optString("app")) || !groupKey.equals(j.optString("key"))) continue;
                String ip=p.getAddress().getHostAddress(); int port=j.optInt("port",9531);
                if (ip.equals(getLanIp()) && port==httpPort) continue;
                peers.put(ip+":"+port,new Peer(j.optString("name","Android"),ip,port));
            } catch(Exception e) { Log.w(TAG,"recv "+e.getMessage()); }
        } catch(Exception e) { Log.e(TAG,"listen "+e.getMessage()); }
    }
    private void announce() {
        try (DatagramSocket s=new DatagramSocket()) {
            s.setBroadcast(true);
            JSONObject j=new JSONObject(); j.put("app","minis-notify-v1");j.put("key",groupKey);j.put("port",httpPort);j.put("name",Build.MODEL);
            byte[] b=j.toString().getBytes("UTF-8");
            s.send(new DatagramPacket(b,b.length,InetAddress.getByName("255.255.255.255"),DISCOVERY_PORT));
        } catch(Exception e) { Log.w(TAG,"announce "+e.getMessage()); }
    }
    private void clean() { long now=System.currentTimeMillis(); for(Map.Entry<String,Peer> e:peers.entrySet()) if(now-e.getValue().seen>30000) peers.remove(e.getKey()); }
    public String peersJson() {
        clean(); StringBuilder s=new StringBuilder("["); int i=0;
        for(Peer p:peers.values()) { if(i++>0)s.append(','); s.append("{\"name\":\"").append(esc(p.name)).append("\",\"ip\":\"").append(p.ip).append("\",\"port\":").append(p.port).append("}"); }
        return s.append(']').toString();
    }
    public int broadcast(String title,String body) { int ok=0; for(Peer p:peers.values()) if(send(p.ip,p.port,title,body))ok++; return ok; }
    public boolean send(String ip,int port,String title,String body) {
        try {
            URL u=new URL("http://"+ip+":"+port+"/"); HttpURLConnection c=(HttpURLConnection)u.openConnection(); c.setConnectTimeout(3000);c.setReadTimeout(5000);c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");
            JSONObject j=new JSONObject();j.put("title",title);j.put("body",body);j.put("relay_key",groupKey);
            try(OutputStream o=c.getOutputStream()){o.write(j.toString().getBytes("UTF-8"));}
            return c.getResponseCode()==200;
        } catch(Exception e) { Log.w(TAG,"send "+e.getMessage()); return false; }
    }
    private String getLanIp() { try { for(NetworkInterface n:Collections.list(NetworkInterface.getNetworkInterfaces())) for(InetAddress a:Collections.list(n.getInetAddresses())) if(a instanceof Inet4Address&&!a.isLoopbackAddress()) return a.getHostAddress(); }catch(Exception e){} return ""; }
    private String esc(String x) { return x.replace("\\","\\\\").replace("\"","\\\""); }
}
