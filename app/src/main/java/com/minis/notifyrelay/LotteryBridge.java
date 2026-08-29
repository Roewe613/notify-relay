package com.minis.notifyrelay;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONArray;
import org.json.JSONObject;

/** Android WebView只读公开开奖记录；不登录、不下注、不保存网页Cookie。 */
public class LotteryBridge {
    private static LotteryBridge shared;
    private static final String BASE = "https://pedzi.fal0q.49493311.com/kaicaiwang/#/lotteryrecord?lotteryTypeCode=SSC&lotteryCode=";
    private final Activity activity;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView web;
    private volatile boolean syncing;
    private volatile String currentCode = "";
    private volatile String lastError = "";

    public static synchronized LotteryBridge create(Activity a) {
        if (shared == null) shared = new LotteryBridge(a);
        return shared;
    }
    public static LotteryBridge get() { return shared; }

    private LotteryBridge(Activity a) {
        activity = a;
        prefs = a.getSharedPreferences("notify_relay", Context.MODE_PRIVATE);
        main.post(() -> { ensureWebView(); scheduleNext(3000); });
    }

    private void ensureWebView() {
        if (web != null) return;
        web = new WebView(activity);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.clearCache(true); web.clearHistory();
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                main.postDelayed(() -> extract(currentCode), 2500);
            }
            @Override public void onReceivedError(WebView v, int code, String desc, String url) {
                fail(currentCode + ": " + desc);
            }
        });
    }

    public synchronized boolean requestSync() {
        if (syncing) return false;
        syncing = true; lastError = "";
        main.post(() -> load("TJSSC"));
        return true;
    }

    private void load(String code) {
        ensureWebView(); currentCode = code;
        web.stopLoading(); web.loadUrl(BASE + code);
        main.postDelayed(() -> {
            if (syncing && code.equals(currentCode)) fail(code + ": 页面加载超时");
        }, 18000);
    }

    private void extract(String code) {
        if (!syncing || !code.equals(currentCode)) return;
        String js = "(()=>{const t=document.body.innerText,r=[],re=/(\\d{2}:\\d{2}:\\d{2})\\s+(\\d{7})\\s+([0-9])\\s+([0-9])\\s+([0-9])\\s+([0-9])\\s+([0-9])/g;let m;while((m=re.exec(t))&&r.length<5)r.push({issue:'" +
            java.time.Year.now().getValue() + "'+m[2],openNum:m.slice(3,8).map(Number),openDateTime:m[1],source:'android_webview'});return JSON.stringify(r)})()";
        web.evaluateJavascript(js, raw -> {
            try {
                String decoded = new JSONArray("[" + raw + "]").getString(0);
                JSONArray rows = new JSONArray(decoded);
                if (rows.length() < 1) throw new Exception("未解析到开奖记录");
                prefs.edit().putString("lottery_" + code, rows.toString())
                    .putLong("lottery_" + code + "_time", System.currentTimeMillis()).apply();
                if ("TJSSC".equals(code)) main.post(() -> load("XJSSC"));
                else finishSuccess();
            } catch (Exception e) { fail(code + ": " + e.getMessage()); }
        });
    }

    private synchronized void finishSuccess() {
        syncing = false; currentCode = "";
        prefs.edit().putLong("lottery_last_sync", System.currentTimeMillis()).putString("lottery_last_error", "").apply();
        scheduleNext(intervalMs());
    }
    private synchronized void fail(String error) {
        if (!syncing) return;
        syncing = false; lastError = error;
        prefs.edit().putString("lottery_last_error", error).apply();
        scheduleNext(intervalMs());
    }
    private long intervalMs() { return prefs.getInt("lottery_interval_min", 20) * 60000L; }
    private void scheduleNext(long delay) { main.removeCallbacks(autoSync); main.postDelayed(autoSync, delay); }
    private final Runnable autoSync = this::requestSync;

    public String getRows(String code) {
        return prefs.getString("lottery_" + code, "[]");
    }
    public String statusJson() {
        try {
            return new JSONObject().put("ok", true).put("syncing", syncing)
                .put("current", currentCode).put("interval_min", prefs.getInt("lottery_interval_min", 20))
                .put("last_sync", prefs.getLong("lottery_last_sync", 0))
                .put("last_error", prefs.getString("lottery_last_error", lastError))
                .put("tj_time", prefs.getLong("lottery_TJSSC_time", 0))
                .put("xj_time", prefs.getLong("lottery_XJSSC_time", 0)).toString();
        } catch (Exception e) { return "{\"ok\":false}"; }
    }
    public boolean setInterval(int minutes) {
        if (minutes < 5 || minutes > 60) return false;
        prefs.edit().putInt("lottery_interval_min", minutes).apply(); scheduleNext(minutes * 60000L); return true;
    }
}
