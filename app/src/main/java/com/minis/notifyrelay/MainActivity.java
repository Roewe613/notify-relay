package com.minis.notifyrelay;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
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
        
        Intent serviceIntent = new Intent(this, NotifyRelayService.class);
        startService(serviceIntent);
        Log.i(TAG, "Service started");
        
        try { Thread.sleep(1500); } catch (InterruptedException e) {}
        
        WebView webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.setWebViewClient(new WebViewClient());
        // 用默认端口9531, 不跟Minis的9530冲突
        webView.loadUrl("http://127.0.0.1:9531/");
        setContentView(webView);
        Log.i(TAG, "WebView loaded");
    }
}
