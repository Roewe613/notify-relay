package com.minis.notifyrelay;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    private static final String TAG = "NotifyRelay";
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "MainActivity onCreate");
        
        // 启动服务
        Intent serviceIntent = new Intent(this, NotifyRelayService.class);
        startService(serviceIntent);
        Log.i(TAG, "Service started");
        
        // 等服务起来
        try { Thread.sleep(1000); } catch (InterruptedException e) {}
        
        // Web UI
        WebView webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.setWebViewClient(new WebViewClient());
        webView.loadUrl("http://127.0.0.1:9530/");
        setContentView(webView);
        Log.i(TAG, "WebView loaded");
    }
}
