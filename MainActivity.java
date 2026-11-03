package com.senator.captainpro;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import android.os.Vibrator;
import android.os.Build;
import android.os.VibrationEffect;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private WebView webView;
    private SharedPreferences prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("captain_settings", MODE_PRIVATE);
        webView = new WebView(this);
        webView.setBackgroundColor(0xff090d16);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new Bridge(), "Captain");
        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }

    private class Bridge {
        @JavascriptInterface public String getSavedIp() {
            return prefs.getString("ip", "192.168.1.18");
        }

        @JavascriptInterface public void saveIp(String ip) {
            if (validIp(ip)) prefs.edit().putString("ip", ip).apply();
        }

        @JavascriptInterface public void sendKey(String ip, String key, String label) {
            if (!validIp(ip)) {
                report(false, "عنوان IP غير صحيح");
                return;
            }
            executor.execute(() -> {
                boolean ok = false;
                String msg;
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(ip, 20000), 2500);
                    socket.setSoTimeout(2500);
                    OutputStream out = socket.getOutputStream();
                    String handshake = "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><Command request=\"998\"><data>SM-A266B</data><uuid>1596d3cc-7643-4a03-8dcd-03bedbaaef62-02:00:00:00:00:00</uuid></Command>";
                    out.write(packet(handshake));
                    out.flush();
                    Thread.sleep(60);
                    String command = "{\"array\":[{\"KeyValue\":\"" + key + "\"}],\"request\":\"1040\"}";
                    out.write(packet(command));
                    out.flush();
                    ok = true;
                    msg = "تم إرسال الأمر: " + label;
                } catch (Exception e) {
                    msg = "تعذر الاتصال: " + (e.getMessage() == null ? "تحقق من الشبكة والرسيفر" : e.getMessage());
                }
                report(ok, msg);
            });
        }
    }

    private byte[] packet(String text) {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        String header = String.format(java.util.Locale.US, "Start%07dEnd", body.length);
        byte[] h = header.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[h.length + body.length];
        System.arraycopy(h, 0, result, 0, h.length);
        System.arraycopy(body, 0, result, h.length, body.length);
        return result;
    }

    private boolean validIp(String ip) {
        if (ip == null || !ip.matches("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$")) return false;
        String[] parts = ip.split("\\.");
        for (String p : parts) if (Integer.parseInt(p) > 255) return false;
        return true;
    }

    private void report(boolean ok, String msg) {
        main.post(() -> {
            if (webView != null) {
                String safe = JSONObject.quote(msg);
                webView.evaluateJavascript("window.nativeResult(" + ok + "," + safe + ")", null);
            }
        });
    }

    @Override protected void onDestroy() {
        executor.shutdownNow();
        if (webView != null) { webView.removeJavascriptInterface("Captain"); webView.destroy(); }
        super.onDestroy();
    }
}
