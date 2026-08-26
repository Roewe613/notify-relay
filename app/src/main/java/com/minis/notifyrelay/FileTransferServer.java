package com.minis.notifyrelay;

import android.content.Context;
import android.util.Log;
import java.io.*;
import java.net.*;
import java.util.*;

/** 独立二进制文件服务器：与9531通知接口分离，避免大文件阻塞通知。 */
public class FileTransferServer {
    private static final String TAG="HubFile";
    private final Context context; private final int port;
    private volatile boolean running;
    public FileTransferServer(Context c,int port,String key){context=c;this.port=port;}
    public void start(){ if(running)return;running=true;new Thread(this::loop,"hub-file-server").start(); }
    private void loop(){
        try(ServerSocket ss=new ServerSocket(port,10,InetAddress.getByName("0.0.0.0"))){
            while(running){try{Socket s=ss.accept();new Thread(()->receive(s)).start();}catch(Exception e){}}
        }catch(Exception e){Log.e(TAG,"server "+e.getMessage());}
    }
    private void receive(Socket s){
        try{
            DataInputStream in=new DataInputStream(new BufferedInputStream(s.getInputStream()));
            String magic=in.readUTF(); String recvKey=in.readUTF(); String name=in.readUTF(); String mime=in.readUTF(); long size=in.readLong();
            String key=context.getSharedPreferences("notify_relay",Context.MODE_PRIVATE).getString("lan_key","");
            if(!"HUBFILE1".equals(magic)||!key.equals(recvKey)||size<0||size>1024L*1024L*1024L){s.close();return;}
            name=name.replaceAll("[^a-zA-Z0-9._-]","_"); if(name.isEmpty())name="received_file";
            File dir=new File(context.getFilesDir(),"received");dir.mkdirs();File f=new File(dir,System.currentTimeMillis()+"_"+name);
            try(OutputStream out=new BufferedOutputStream(new FileOutputStream(f))){byte[] b=new byte[32768];long left=size;while(left>0){int n=in.read(b,0,(int)Math.min(b.length,left));if(n<0)throw new EOFException();out.write(b,0,n);left-=n;}}
            s.getOutputStream().write(1);
            android.app.NotificationManager nm=(android.app.NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
            if(nm!=null){
                android.app.Notification n=new android.app.Notification.Builder(context,"panel_notify")
                    .setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("📥 收到局域网文件")
                    .setContentText(name+" ("+(size/1024/1024)+"MB)").setAutoCancel(true).build();
                nm.notify((int)(System.currentTimeMillis()%100000),n);
            }
            Log.i(TAG,"received "+f.getName()+" "+mime);
        }catch(Exception e){Log.w(TAG,"receive "+e.getMessage());}finally{try{s.close();}catch(Exception e){}}
    }
}
