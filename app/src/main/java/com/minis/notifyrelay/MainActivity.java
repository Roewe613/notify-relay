package com.minis.notifyrelay;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    private static final String TAG = "NotifyRelay";
    private NotifyServer server;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "MainActivity onCreate");

        // HTTP server 直接跑在 Activity 进程里, 不依赖 Service
        server = new NotifyServer(this);
        server.start();
        Log.i(TAG, "Server started on port " + server.getPort());

        // 也尝试启动 Service 做后台保活
        try {
            startService(new Intent(this, NotifyRelayService.class));
        } catch (Exception e) {
            Log.w(TAG, "Service start failed (ok, Activity has server): " + e.getMessage());
        }

        WebView webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(true);
        webView.setWebViewClient(new WebViewClient());
        webView.loadUrl("http://127.0.0.1:" + server.getPort() + "/");
        setContentView(webView);
        Log.i(TAG, "WebView loaded");
    }

    @Override
    protected void onDestroy() {
        // Activity 关闭时不停 server, 让 Service 接管
        // 如果 Service 没活, server 也会随进程死
        super.onDestroy();
    }
}
