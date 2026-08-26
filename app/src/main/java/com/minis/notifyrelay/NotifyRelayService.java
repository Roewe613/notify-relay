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
        if (server != null) server.stop();
        super.onDestroy();
    }
}
