package com.minis.notifyrelay;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.Manifest;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    private static final String TAG = "NotifyRelay";
    private static final int REQ_NOTIF = 1001;
    private NotifyServer server;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "MainActivity onCreate");

        // 1. 请求通知权限 (Android 13+)
        requestNotificationPermission();

        // 2. Activity 与后台Service共用同一台HTTP/LAN server
        server = NotifyServer.getShared(this);
        Log.i(TAG, "Server ready on port " + server.getPort());

        // 3. 也启动 Service 做后台保活
        try {
            startService(new Intent(this, NotifyRelayService.class));
        } catch (Exception e) {
            Log.w(TAG, "Service start failed: " + e.getMessage());
        }

        // 4. WebView 加载
        WebView webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(true);
        webView.setWebViewClient(new WebViewClient());
        webView.loadUrl("http://127.0.0.1:" + server.getPort() + "/");
        setContentView(webView);
        Log.i(TAG, "WebView loaded, port=" + server.getPort());
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "Requesting POST_NOTIFICATIONS permission");
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIF) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            Log.i(TAG, "POST_NOTIFICATIONS: " + (granted ? "granted" : "denied"));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}
