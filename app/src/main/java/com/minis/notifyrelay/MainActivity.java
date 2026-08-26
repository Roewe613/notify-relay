package com.minis.notifyrelay;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.Manifest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;
import java.io.*;

public class MainActivity extends Activity {
    private static final String TAG = "NotifyRelay";
    private static final int REQ_NOTIF = 1001;
    private static final int REQ_BG = 1002;
    private static final int REQ_FILE = 1003;
    private NotifyServer server;
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(android.graphics.Color.rgb(8, 13, 24));
        getWindow().setNavigationBarColor(android.graphics.Color.rgb(8, 13, 24));
        requestNotificationPermission();
        server = NotifyServer.getShared(this);
        try { startService(new Intent(this, NotifyRelayService.class)); } catch (Exception e) { Log.w(TAG, "Service: " + e.getMessage()); }

        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(true);
        webView.addJavascriptInterface(new BackgroundBridge(), "NativeHub");
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                applySavedLocalBackground();
            }
        });
        webView.loadUrl("http://127.0.0.1:" + server.getPort() + "/");
        setContentView(webView);
    }

    private class BackgroundBridge {
        @JavascriptInterface public void pickBackground() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                startActivityForResult(i, REQ_BG);
            });
        }
        @JavascriptInterface public void pickFile() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*");
                startActivityForResult(i, REQ_FILE);
            });
        }
        @JavascriptInterface public void clearLocalBackground() {
            getSharedPreferences("notify_relay", MODE_PRIVATE).edit().remove("local_bg").apply();
            runOnUiThread(() -> webView.evaluateJavascript("setLocalBg('')", null));
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == REQ_FILE) {
            try {
                Uri uri = data.getData(); String name = "file";
                android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
                if (c != null) { int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (c.moveToFirst() && i >= 0) name = c.getString(i); c.close(); }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (InputStream in = getContentResolver().openInputStream(uri)) { byte[] b = new byte[8192]; int n; while ((n=in.read(b))>0) { if (out.size()+n > 20*1024*1024) throw new IOException("文件超过20MB"); out.write(b,0,n); } }
                String encoded = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
                int sent = server.broadcastFile(name, getContentResolver().getType(uri), encoded);
                final String msg = "文件 " + name + " 已发送给 " + sent + " 台在线手机";
                webView.evaluateJavascript("fileResult(" + JSONObject.quote(msg) + ")", null);
            } catch (Exception e) { webView.evaluateJavascript("fileResult(" + JSONObject.quote("发送失败：" + e.getMessage()) + ")", null); }
            return;
        }
        if (requestCode != REQ_BG) return;
        try {
            Uri uri = data.getData();
            File dst = new File(getFilesDir(), "hub_background.jpg");
            try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[8192]; int n; long total = 0;
                while ((n = in.read(buf)) > 0) { total += n; if (total > 8 * 1024 * 1024) throw new IOException("图片超过8MB"); out.write(buf, 0, n); }
            }
            getSharedPreferences("notify_relay", MODE_PRIVATE).edit().putString("local_bg", dst.getAbsolutePath()).apply();
            applySavedLocalBackground();
        } catch (Exception e) { Log.e(TAG, "background", e); }
    }

    private void applySavedLocalBackground() {
        String path = getSharedPreferences("notify_relay", MODE_PRIVATE).getString("local_bg", "");
        if (path.isEmpty() || !new File(path).exists()) return;
        String js = "setLocalBg(" + JSONObject.quote("file://" + path) + ")";
        webView.evaluateJavascript(js, null);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
    }
}
