package com.minis.notifyrelay;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

public class NotifyRelayService extends Service {
    private static final String TAG = "NotifyRelay";
    private NotifyServer server;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        server = NotifyServer.getShared(this);
        Log.i(TAG, "Service sharing server on port " + server.getPort());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        // server 为 Activity/Service 共享实例；Service 被系统回收时不能关闭它。
        // 否则前台界面还在，但 9531 会被错误关闭。
        Log.i(TAG, "Service destroyed; shared server remains managed by app process");
        super.onDestroy();
    }
}
