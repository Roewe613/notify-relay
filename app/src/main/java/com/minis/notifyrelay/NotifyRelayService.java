package com.minis.notifyrelay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/** 前台保活：让ColorOS不会冻结9531通知服务器。 */
public class NotifyRelayService extends Service {
    private static final String TAG = "NotifyRelay";
    private static final String CHANNEL = "hub_service";
    private static final int ID = 8101;
    private NotifyServer server;
    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(new NotificationChannel(CHANNEL, "通知枢纽服务", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("通知枢纽运行中")
            .setContentText("本机通知服务 9531")
            .setOngoing(true).build();
        startForeground(ID, n);
        server = NotifyServer.getShared(this);
        server.start();
        Log.i(TAG, "Foreground service owns server on " + server.getPort());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }
    @Override public void onDestroy() {
        // 共享server保留给应用进程与watchdog；系统重启Service后自动接管。
        Log.i(TAG, "Foreground service destroyed");
        super.onDestroy();
    }
}
