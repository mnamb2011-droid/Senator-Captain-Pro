
package com.senator.captainpro;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.content.SharedPreferences;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.InflaterInputStream;

public class MainActivity extends Activity {

    private WebView webView;
    private SharedPreferences prefs;

    // تنفيذ جميع أوامر الرسيفر بالترتيب
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    private final Handler main =
            new Handler(Looper.getMainLooper());

    // إعدادات بروتوكول G-MScreen
    private static final int GMSCREEN_PORT = 20000;
    private static final int CONNECT_TIMEOUT = 5000;
    private static final int READ_TIMEOUT = 5000;
    private static final int GREETING_SIZE = 108;
    private static final int MAX_RESPONSE_SIZE = 1048576;

    // إعادة إنشاء الجلسة بعد 45 ثانية من الخمول
    private static final long IDLE_RECONNECT_MS = 45000;

    // الاتصال المستمر بالرسيفر
    private Socket receiverSocket;
    private InputStream receiverInput;
    private OutputStream receiverOutput;

    private String connectedIp = null;
    private boolean sessionReady = false;

    // وقت آخر أمر ناجح أُرسل للرسيفر
    private long lastKeySentAt = 0;

    private String currentStage = "جاهز";

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

    // =====================================================
    // JavaScript Bridge
    // =====================================================

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
                report(false, "عنوان IP غير صحيح");
                return;
            }

            if (key == null || !key.matches("[0-9]{1,4}")) {
                report(false, "كود الزر غير صحيح");
                return;
            }

            executor.execute(() -> {

                try {

                    report(
                            false,
                            "جاري تجهيز الاتصال وإرسال الأمر..."
                    );

                    sendKeyWithRecovery(ip, key);

                    report(
                            true,
                            "تم إرسال الأمر: " + label
                                    + "\nاتصال G-MScreen نشط"
                    );

                } catch (Exception e) {

                    String detail = e.getMessage();

                    String message =
                            "فشل تنفيذ الأمر"
                                    + "\nالمرحلة: " + currentStage
                                    + "\nالخطأ: "
                                    + e.getClass().getSimpleName()
                                    + "\nالتفاصيل: "
                                    + (
                                        detail == null
                                        ? "غير متوفرة"
                                        : detail
                                    );

                    closeSession();

                    report(false, message);
                }
            });
        }
    }

    // =====================================================
    // إرسال الأمر مع معالجة الاتصال القديم
    // =====================================================

    private void sendKeyWithRecovery(
            String ip,
            String key
    ) throws Exception {

        boolean idleExpired =
                lastKeySentAt == 0
                || SystemClock.elapsedRealtime() - lastKeySentAt
                        >= IDLE_RECONNECT_MS;

        boolean needNewConnection =
                !isSessionReady(ip) || idleExpired;

        // إذا انتهت مدة الخمول، لا نستخدم الجلسة القديمة
        if (needNewConnection) {

            closeSession();

            currentStage = "إعادة إنشاء جلسة G-MScreen";

            connectSession(ip);
        }

        // محاولة إرسال الأمر
        try {

            sendRemoteKey(key);

            lastKeySentAt = SystemClock.elapsedRealtime();

        } catch (Exception firstError) {

            // فشل الإرسال: إغلاق الجلسة القديمة
            closeSession();

            currentStage = "إعادة الاتصال بعد فشل الإرسال";

            // إنشاء جلسة جديدة
            connectSession(ip);

            // إعادة إرسال الأمر مرة واحدة
            currentStage = "إعادة إرسال أمر التحكم";

            sendRemoteKey(key);

            lastKeySentAt = SystemClock.elapsedRealtime();
        }
    }

    // =====================================================
    // إنشاء جلسة G-MScreen
    // =====================================================

    private void connectSession(String ip) throws Exception {

        currentStage = "فتح اتصال TCP";

        Socket socket = new Socket();

        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        socket.setSoTimeout(READ_TIMEOUT);

        socket.connect(
                new InetSocketAddress(ip, GMSCREEN_PORT),
                CONNECT_TIMEOUT
        );

        receiverSocket = socket;
        receiverInput = socket.getInputStream();
        receiverOutput = socket.getOutputStream();

        connectedIp = ip;
        sessionReady = false;

        // الخطوة 1: إرسال مصافحة XML رقم 998

        currentStage = "إرسال المصافحة 998";

        String handshake =
                "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>"
                        + "<Command request=\"998\" />";

        writeFrame(handshake);

        // الخطوة 2: استقبال رسالة الترحيب 108 بايت

        currentStage = "استقبال رسالة الترحيب 108 بايت";

        byte[] greeting = readExact(
                receiverInput,
                GREETING_SIZE
        );

        if (greeting.length != GREETING_SIZE) {
            throw new Exception(
                    "حجم رسالة الترحيب غير صحيح"
            );
        }

        if (greeting[0] != '[' || greeting[1] != '[') {
            throw new Exception(
                    "رسالة الترحيب لا تبدأ بالعلامة المتوقعة [["
            );
        }

        // الخطوة 3: إرسال طلب معلومات الجهاز 15

        currentStage = "إرسال طلب معلومات الجهاز 15";

        writeFrame("{\"request\":\"15\"}");

        // الخطوة 4: استقبال رد GCDH

        currentStage = "استقبال رد طلب 15";

        byte[] responsePacket = readResponsePacket(
                receiverInput
        );

        if (responsePacket.length == 0) {
            throw new Exception(
                    "الرسيفر أرسل إقراراً فارغاً"
            );
        }

        // الخطوة 5: فك الضغط وتحليل الرد

        currentStage = "فك ضغط وتحليل معلومات الرسيفر";

        String response = decodeResponseBytes(
                responsePacket
        ).trim();

        if (response.isEmpty()) {
            throw new Exception(
                    "رد معلومات الرسيفر فارغ"
            );
        }

        Object parsed = new JSONTokener(response).nextValue();

        if (!(parsed instanceof JSONObject)
                && !(parsed instanceof org.json.JSONArray)) {

            throw new Exception(
                    "رد طلب 15 ليس JSON صالحاً: " + response
            );
        }

        sessionReady = true;

        currentStage = "الجلسة جاهزة";
    }

    // =====================================================
    // إرسال أمر الريموت 1040
    // =====================================================

    private void sendRemoteKey(String key) throws Exception {

        if (!isSessionReady(connectedIp)) {
            throw new Exception(
                    "لا توجد جلسة G-MScreen نشطة"
            );
        }

        currentStage = "إرسال أمر التحكم 1040";

        String command =
                "{\"request\":\"1040\",\"array\":["
                        + "{\"KeyValue\":\""
                        + key
                        + "\"}]}";

        // إرسال الأمر دون انتظار رد من الرسيفر
        writeFrame(command);
    }

    // =====================================================
    // التحقق من صلاحية الجلسة
    // =====================================================

    private boolean isSessionReady(String ip) {

        return sessionReady
                && receiverSocket != null
                && receiverSocket.isConnected()
                && !receiverSocket.isClosed()
                && !receiverSocket.isOutputShutdown()
                && !receiverSocket.isInputShutdown()
                && receiverInput != null
                && receiverOutput != null
                && ip != null
                && ip.equals(connectedIp);
    }

    // =====================================================
    // إرسال البيانات بإطار G-MScreen
    // =====================================================

    private void writeFrame(String payload) throws Exception {

        if (receiverOutput == null) {
            throw new Exception(
                    "مجرى إرسال البيانات غير متاح"
            );
        }

        byte[] body = payload.getBytes(
                StandardCharsets.UTF_8
        );

        String header = String.format(
                Locale.US,
                "Start%07dEnd",
                body.length
        );

        byte[] headerBytes = header.getBytes(
                StandardCharsets.US_ASCII
        );

        receiverOutput.write(headerBytes);
        receiverOutput.write(body);
        receiverOutput.flush();
    }

    // =====================================================
    // استقبال عدد محدد من البايتات
    // =====================================================

    private byte[] readExact(
            InputStream input,
            int length
    ) throws Exception {

        if (length < 0 || length > MAX_RESPONSE_SIZE) {
            throw new Exception(
                    "حجم بيانات غير مسموح به"
            );
        }

        byte[] result = new byte[length];

        int offset = 0;

        while (offset < length) {

            int count = input.read(
                    result,
                    offset,
                    length - offset
            );

            if (count < 0) {
                throw new EOFException(
                        "انقطع الاتصال قبل اكتمال البيانات: "
                                + offset + "/" + length
                );
            }

            if (count == 0) {

                int one = input.read();

                if (one < 0) {
                    throw new EOFException(
                            "انقطع الاتصال أثناء استقبال البيانات"
                    );
                }

                result[offset++] = (byte) one;

            } else {
                offset += count;
            }
        }

        return result;
    }

    // =====================================================
    // قراءة رد GCDH
    // =====================================================

    private byte[] readResponsePacket(
            InputStream input
    ) throws Exception {

        byte[] prefix = readExact(input, 4);

        String magic = new String(
                prefix,
                StandardCharsets.US_ASCII
        );

        if (!"GCDH".equals(magic)) {
            throw new Exception(
                    "ترويسة رد الرسيفر غير صحيحة: " + magic
            );
        }

        byte[] remainingHeader = readExact(input, 12);

        byte[] header = new byte[16];

        System.arraycopy(
                prefix,
                0,
                header,
                0,
                4
        );

        System.arraycopy(
                remainingHeader,
                0,
                header,
                4,
                12
        );

        // طول البيانات Little Endian

        int bodyLength =
                (header[4] & 0xff)
                | ((header[5] & 0xff) << 8);

        if (bodyLength <= 0 || bodyLength > MAX_RESPONSE_SIZE) {
            throw new Exception(
                    "طول بيانات GCDH غير صالح: " + bodyLength
            );
        }

        return readExact(input, bodyLength);
    }

    // =====================================================
    // فك ضغط Zlib وتحليل رد الرسيفر
    // =====================================================

    private String decodeResponseBytes(
            byte[] bytes
    ) throws Exception {

        if (bytes == null || bytes.length == 0) {
            return "";
        }

        boolean isZlib = false;

        if (bytes.length >= 2) {

            int cmf = bytes[0] & 0xff;
            int flg = bytes[1] & 0xff;

            isZlib =
                    (cmf & 0x0f) == 8
                    && (cmf >> 4) <= 7
                    && (((cmf << 8) + flg) % 31 == 0);
        }

        String decoded;

        if (isZlib) {

            try {
                decoded = decompressZlib(bytes);

            } catch (Exception e) {
                throw new Exception(
                        "فشل فك ضغط رد Zlib: "
                                + e.getMessage()
                );
            }

        } else {

            decoded = new String(
                    bytes,
                    StandardCharsets.UTF_8
            );
        }

        if (decoded.startsWith("Start")
                && decoded.length() >= 15
                && decoded.substring(12, 15).equals("End")) {

            decoded = decoded.substring(15);
        }

        return decoded;
    }

    private String decompressZlib(
            byte[] bytes
    ) throws Exception {

        try (
                InflaterInputStream inflater =
                        new InflaterInputStream(
                                new ByteArrayInputStream(bytes)
                        );

                ByteArrayOutputStream output =
                        new ByteArrayOutputStream()
        ) {

            byte[] buffer = new byte[4096];

            int count;
            int total = 0;

            while ((count = inflater.read(buffer)) != -1) {

                total += count;

                if (total > MAX_RESPONSE_SIZE) {
                    throw new Exception(
                            "حجم البيانات بعد فك الضغط تجاوز الحد المسموح"
                    );
                }

                output.write(buffer, 0, count);
            }

            return output.toString(
                    StandardCharsets.UTF_8.name()
            );
        }
    }

    // =====================================================
    // إغلاق الجلسة
    // =====================================================

    private void closeSession() {

        sessionReady = false;
        connectedIp = null;
        lastKeySentAt = 0;

        try {
            if (receiverInput != null) {
                receiverInput.close();
            }
        } catch (Exception ignored) {
        }

        try {
            if (receiverOutput != null) {
                receiverOutput.close();
            }
        } catch (Exception ignored) {
        }

        try {
            if (receiverSocket != null) {
                receiverSocket.close();
            }
        } catch (Exception ignored) {
        }

        receiverInput = null;
        receiverOutput = null;
        receiverSocket = null;
    }

    // =====================================================
    // التحقق من IP
    // =====================================================

    private boolean validIp(String ip) {

        if (ip == null
                || !ip.matches(
                    "^[0-9]{1,3}(\\.[0-9]{1,3}){3}$"
                )) {
            return false;
        }

        String[] parts = ip.split("\\.");

        for (String part : parts) {

            int value = Integer.parseInt(part);

            if (value > 255) {
                return false;
            }
        }

        return true;
    }

    // =====================================================
    // عرض نتيجة العملية في واجهة الريموت
    // =====================================================

    private void report(
            boolean ok,
            String message
    ) {

        main.post(() -> {

            if (webView != null) {

                String safe = JSONObject.quote(message);

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

    // =====================================================
    // إغلاق التطبيق
    // =====================================================

    @Override
    protected void onDestroy() {

        executor.execute(this::closeSession);

        executor.shutdown();

        if (webView != null) {

            webView.removeJavascriptInterface(
                    "Captain"
            );

            webView.destroy();
            webView = null;
        }

        super.onDestroy();
    }
}
