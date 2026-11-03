
package com.senator.captainpro;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    private final Handler main =
            new Handler(Looper.getMainLooper());

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences(
                "captain_settings",
                MODE_PRIVATE
        );

        webView = new WebView(this);
        webView.setBackgroundColor(0xff090d16);

        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient());

        webView.addJavascriptInterface(
                new Bridge(),
                "Captain"
        );

        setContentView(webView);

        webView.loadUrl(
                "file:///android_asset/index.html"
        );
    }

    private class Bridge {

        @JavascriptInterface
        public String getSavedIp() {
            return prefs.getString(
                    "ip",
                    "192.168.1.18"
            );
        }

        @JavascriptInterface
        public void saveIp(String ip) {
            if (validIp(ip)) {
                prefs.edit()
                        .putString("ip", ip)
                        .apply();
            }
        }

        @JavascriptInterface
        public void sendKey(
                String ip,
                String key,
                String label
        ) {

            if (!validIp(ip)) {
                report(
                        false,
                        "عنوان IP غير صحيح"
                );
                return;
            }

            executor.execute(() -> {

                boolean ok = false;

                String msg = "";
                String stage = "بدء الاتصال";

                try (Socket socket = new Socket()) {

                    // المرحلة الأولى: فتح اتصال TCP
                    stage = "فتح اتصال TCP مع "
                            + ip + ":20000";

                    socket.connect(
                            new InetSocketAddress(ip, 20000),
                            5000
                    );

                    // مهلة استقبال البيانات
                    stage = "ضبط مهلة الاتصال";

                    socket.setSoTimeout(5000);

                    OutputStream out =
                            socket.getOutputStream();

                    // المرحلة الثانية: إرسال المصافحة 998
                    stage = "إرسال طلب المصافحة 998";

                    String handshake =
                            "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>"
                            + "<Command request=\"998\">"
                            + "<data>SM-A266B</data>"
                            + "<uuid>1596d3cc-7643-4a03-8dcd-03bedbaaef62-02:00:00:00:00:00</uuid>"
                            + "</Command>";

                    out.write(packet(handshake));
                    out.flush();

                    // مهلة قصيرة بعد إرسال المصافحة
                    stage = "الانتظار القصير بعد المصافحة";

                    Thread.sleep(300);

                    // المرحلة الثالثة: إرسال أمر التحكم
                    stage = "إرسال أمر التحكم 1040";

                    String command =
                            "{\"array\":[{\"KeyValue\":\""
                            + key
                            + "\"}],\"request\":\"1040\"}";

                    out.write(packet(command));
                    out.flush();

                    ok = true;

                    msg = "تم إرسال الأمر: " + label
                            + "\nملاحظة: نجاح الإرسال لا يؤكد تنفيذ الرسيفر للأمر.";

                } catch (Exception e) {

                    String detail = e.getMessage();

                    msg = "فشل الاتصال"
                            + "\nالمرحلة: " + stage
                            + "\nنوع الخطأ: "
                            + e.getClass().getSimpleName()
                            + "\nالتفاصيل: "
                            + (
                                detail == null || detail.isEmpty()
                                ? "لا توجد تفاصيل إضافية"
                                : detail
                            );
                }

                report(ok, msg);
            });
        }
    }

    // بناء حزمة البيانات
    private byte[] packet(String text) {

        byte[] body =
                text.getBytes(StandardCharsets.UTF_8);

        String header = String.format(
                java.util.Locale.US,
                "Start%07dEnd",
                body.length
        );

        byte[] h =
                header.getBytes(StandardCharsets.UTF_8);

        byte[] result =
                new byte[h.length + body.length];

        System.arraycopy(
                h,
                0,
                result,
                0,
                h.length
        );

        System.arraycopy(
                body,
                0,
                result,
                h.length,
                body.length
        );

        return result;
    }

    // التحقق من صحة عنوان IP
    private boolean validIp(String ip) {

        if (ip == null ||
                !ip.matches("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$")) {
            return false;
        }

        String[] parts = ip.split("\\.");

        for (String p : parts) {
            if (Integer.parseInt(p) > 255) {
                return false;
            }
        }

        return true;
    }

    // عرض نتيجة الاتصال داخل التطبيق
    private void report(
            boolean ok,
            String msg
    ) {

        main.post(() -> {

            if (webView != null) {

                String safe =
                        JSONObject.quote(msg);

                webView.evaluateJavascript(
                        "window.nativeResult("
                                + ok
                                + ","
                                + safe
                                + ")",
                        null
                );
            }
        });
    }

    @Override
    protected void onDestroy() {

        executor.shutdownNow();

        if (webView != null) {

            webView.removeJavascriptInterface(
                    "Captain"
            );

            webView.destroy();
        }

        super.onDestroy();
    }
}
