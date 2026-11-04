
package com.senator.captainpro;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.net.DhcpInfo;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.TimeUnit;
import java.util.zip.InflaterInputStream;

public class MainActivity extends Activity {

    private WebView webView;
    private SharedPreferences prefs;

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    private final Handler main =
            new Handler(Looper.getMainLooper());

    private static final int GMSCREEN_PORT = 20000;
    private static final int CONNECT_TIMEOUT = 5000;
    private static final int READ_TIMEOUT = 5000;
    private static final int GREETING_SIZE = 108;
    private static final int MAX_RESPONSE_SIZE = 1048576;

    private static final long IDLE_RECONNECT_MS = 45000;

    private Socket receiverSocket;
    private InputStream receiverInput;
    private OutputStream receiverOutput;

    private String connectedIp = null;
    private boolean sessionReady = false;
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
            return prefs.getString("ip", "");
        }

        @JavascriptInterface
        public void saveIp(String ip) {
            if (validIp(ip)) {
                prefs.edit().putString("ip", ip).apply();
            }
        }

        @JavascriptInterface
        public void sendKey(
                String ip,
                String key,
                String label
        ) {

            if (key == null || !key.matches("[0-9]{1,4}")) {
                report(false, "كود الزر غير صحيح");
                return;
            }

            executor.execute(() -> {

                try {

                    report(
                            false,
                            "جاري البحث عن الرسيفر والاتصال به..."
                    );

                    sendKeyWithRecovery(ip, key);

                    report(
                            true,
                            "تم إرسال الأمر: " + label
                                    + "\nعنوان الرسيفر: " + connectedIp
                                    + "\nاتصال G-MScreen نشط"
                    );

                } catch (Exception e) {

                    String detail = e.getMessage();

                    String message =
                            "تعذر الاتصال بالرسيفر"
                                    + "\nالمرحلة: " + currentStage
                                    + "\nالتفاصيل: "
                                    + (detail == null
                                    ? "غير متوفرة"
                                    : detail);

                    closeSession();
                    report(false, message);
                }
            });
        }
    }

    // =====================================================
    // الاتصال أو البحث التلقائي
    // =====================================================

    private void sendKeyWithRecovery(
            String preferredIp,
            String key
    ) throws Exception {

        boolean idleExpired =
                lastKeySentAt == 0
                || SystemClock.elapsedRealtime() - lastKeySentAt
                        >= IDLE_RECONNECT_MS;

        if (!isSessionReady(connectedIp) || idleExpired) {

            closeSession();

            connectOrDiscover(preferredIp);
        }

        try {

            sendRemoteKey(key);

            lastKeySentAt = SystemClock.elapsedRealtime();

        } catch (Exception firstError) {

            closeSession();

            currentStage = "إعادة الاتصال بعد فشل الإرسال";

            connectOrDiscover(preferredIp);

            currentStage = "إعادة إرسال الأمر";

            sendRemoteKey(key);

            lastKeySentAt = SystemClock.elapsedRealtime();
        }
    }

    // =====================================================
    // الاتصال بالعنوان المحفوظ أو اكتشاف عنوان جديد
    // =====================================================

    private void connectOrDiscover(String preferredIp)
            throws Exception {

        // المحاولة الأولى: آخر IP معروف
        if (validIp(preferredIp)) {

            try {

                currentStage = "تجربة عنوان الرسيفر المحفوظ";

                connectSession(preferredIp);

                saveDiscoveredIp(preferredIp);

                return;

            } catch (Exception ignored) {

                closeSession();
            }
        }

        // المحاولة الثانية: البحث داخل شبكة Wi-Fi
        currentStage = "استخراج نطاق شبكة Wi-Fi";

        List<String> candidates = getLocalNetworkHosts();

        if (candidates.isEmpty()) {
            throw new Exception(
                    "تعذر معرفة نطاق الشبكة. تأكد أن الهاتف متصل بشبكة Wi-Fi"
            );
        }

        currentStage = "البحث عن منفذ G-MScreen 20000";

        List<String> foundHosts = scanForReceiver(candidates);

        if (foundHosts.isEmpty()) {
            throw new Exception(
                    "لم يتم العثور على جهاز يستجيب على منفذ 20000. "
                    + "تأكد أن الهاتف والرسيفر على نفس شبكة Wi-Fi"
            );
        }

        // لا نعتمد على فتح المنفذ فقط.
        // نتحقق من مصافحة G-MScreen لكل جهاز مكتشف.

        for (String candidate : foundHosts) {

            try {

                currentStage =
                        "التحقق من بروتوكول G-MScreen: " + candidate;

                closeSession();

                connectSession(candidate);

                saveDiscoveredIp(candidate);

                return;

            } catch (Exception ignored) {

                closeSession();
            }
        }

        throw new Exception(
                "تم العثور على أجهزة في الشبكة، "
                + "لكن لم ينجح أي منها في مصافحة G-MScreen"
        );
    }

    // =====================================================
    // حفظ عنوان الرسيفر المكتشف
    // =====================================================

    private void saveDiscoveredIp(String ip) {

        if (validIp(ip)) {
            prefs.edit()
                    .putString("ip", ip)
                    .apply();
        }
    }

    // =====================================================
    // استخراج عناوين الأجهزة في شبكة Wi-Fi
    // =====================================================

    private List<String> getLocalNetworkHosts()
            throws Exception {

        WifiManager wifiManager =
                (WifiManager) getApplicationContext()
                        .getSystemService(Context.WIFI_SERVICE);

        if (wifiManager == null) {
            throw new Exception(
                    "خدمة Wi-Fi غير متاحة"
            );
        }

        DhcpInfo dhcp = wifiManager.getDhcpInfo();

        if (dhcp == null || dhcp.ipAddress == 0) {
            throw new Exception(
                    "لم يتم الحصول على معلومات DHCP"
            );
        }

        long phoneIp =
                Integer.toUnsignedLong(dhcp.ipAddress);

        long mask =
                Integer.toUnsignedLong(dhcp.netmask);

        // تحويل صيغة Android ذات الترتيب Little Endian
        phoneIp = Integer.toUnsignedLong(
                Integer.reverseBytes((int) phoneIp)
        );

        mask = Integer.toUnsignedLong(
                Integer.reverseBytes((int) mask)
        );

        // إذا لم يوفر الراوتر Subnet Mask نستخدم /24
        if (mask == 0) {
            mask = 0xFFFFFF00L;
        }

        long network = phoneIp & mask;
        long broadcast = network | (~mask & 0xFFFFFFFFL);

        long hostCount = broadcast - network - 1;

        if (hostCount <= 0) {
            throw new Exception(
                    "نطاق الشبكة غير صالح"
            );
        }

        // حماية من مسح شبكات ضخمة بالخطأ
        if (hostCount > 1022) {
            throw new Exception(
                    "نطاق الشبكة كبير جدًا للبحث التلقائي"
            );
        }

        List<String> hosts = new ArrayList<>();

        String phoneAddress = longToIp(phoneIp);

        for (long address = network + 1;
             address < broadcast;
             address++) {

            String host = longToIp(address);

            if (!host.equals(phoneAddress)) {
                hosts.add(host);
            }
        }

        // تجربة العنوان المحفوظ أولاً إن كان داخل الشبكة
        String savedIp = prefs.getString("ip", "");

        if (validIp(savedIp) && hosts.remove(savedIp)) {
            hosts.add(0, savedIp);
        }

        return hosts;
    }

    private String longToIp(long ip) {

        return ((ip >> 24) & 255) + "."
                + ((ip >> 16) & 255) + "."
                + ((ip >> 8) & 255) + "."
                + (ip & 255);
    }

    // =====================================================
    // فحص الشبكة بالتوازي بحثًا عن المنفذ 20000
    // =====================================================

    private List<String> scanForReceiver(
            List<String> hosts
    ) throws Exception {

        List<String> found = new ArrayList<>();

        ExecutorService scanner =
                Executors.newFixedThreadPool(32);

        ExecutorCompletionService<String> completion =
                new ExecutorCompletionService<>(scanner);

        try {

            for (String host : hosts) {

                completion.submit(() -> {

                    if (probeReceiverPort(host)) {
                        return host;
                    }

                    return null;
                });
            }

            int remaining = hosts.size();

            long deadline =
                    SystemClock.elapsedRealtime() + 10000;

            while (remaining > 0) {

                long timeLeft =
                        deadline - SystemClock.elapsedRealtime();

                if (timeLeft <= 0) {
                    break;
                }

                java.util.concurrent.Future<String> future =
                        completion.poll(
                                timeLeft,
                                TimeUnit.MILLISECONDS
                        );

                if (future == null) {
                    break;
                }

                remaining--;

                try {

                    String host = future.get();

                    if (host != null && !found.contains(host)) {
                        found.add(host);
                    }

                } catch (Exception ignored) {
                }
            }

        } finally {

            scanner.shutdownNow();
        }

        return found;
    }

    // =====================================================
    // اختبار فتح منفذ G-MScreen
    // =====================================================

    private boolean probeReceiverPort(String ip) {

        try (Socket socket = new Socket()) {

            socket.connect(
                    new InetSocketAddress(ip, GMSCREEN_PORT),
                    450
            );

            return socket.isConnected();

        } catch (Exception ignored) {

            return false;
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

        // المصافحة 998

        currentStage = "إرسال المصافحة 998";

        String handshake =
                "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>"
                        + "<Command request=\"998\" />";

        writeFrame(handshake);

        // استقبال الترحيب 108 بايت

        currentStage = "استقبال رسالة الترحيب";

        byte[] greeting = readExact(
                receiverInput,
                GREETING_SIZE
        );

        if (greeting[0] != '[' || greeting[1] != '[') {
            throw new Exception(
                    "رسالة الترحيب غير مطابقة"
            );
        }

        // طلب معلومات الجهاز 15

        currentStage = "إرسال طلب معلومات الجهاز 15";

        writeFrame("{\"request\":\"15\"}");

        // استقبال GCDH

        currentStage = "استقبال رد GCDH";

        byte[] responsePacket =
                readResponsePacket(receiverInput);

        String response =
                decodeResponseBytes(responsePacket).trim();

        if (response.isEmpty()) {
            throw new Exception("رد الرسيفر فارغ");
        }

        Object parsed =
                new JSONTokener(response).nextValue();

        if (!(parsed instanceof JSONObject)
                && !(parsed instanceof org.json.JSONArray)) {

            throw new Exception(
                    "رد الرسيفر ليس JSON صالحًا"
            );
        }

        sessionReady = true;
        currentStage = "الجلسة جاهزة";
    }

    // =====================================================
    // إرسال أمر التحكم 1040
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

        writeFrame(command);
    }

    // =====================================================
    // التحقق من الجلسة
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
    // إرسال إطار G-MScreen
    // =====================================================

    private void writeFrame(String payload) throws Exception {

        if (receiverOutput == null) {
            throw new Exception(
                    "مجرى الإرسال غير متاح"
            );
        }

        byte[] body =
                payload.getBytes(StandardCharsets.UTF_8);

        String header = String.format(
                Locale.US,
                "Start%07dEnd",
                body.length
        );

        receiverOutput.write(
                header.getBytes(StandardCharsets.US_ASCII)
        );

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
                        "انقطع الاتصال أثناء استقبال البيانات"
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

        System.arraycopy(prefix, 0, header, 0, 4);

        System.arraycopy(
                remainingHeader,
                0,
                header,
                4,
                12
        );

        int bodyLength =
                (header[4] & 0xff)
                | ((header[5] & 0xff) << 8);

        if (bodyLength <= 0
                || bodyLength > MAX_RESPONSE_SIZE) {

            throw new Exception(
                    "طول بيانات GCDH غير صالح: " + bodyLength
            );
        }

        return readExact(input, bodyLength);
    }

    // =====================================================
    // فك ضغط Zlib
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
            decoded = decompressZlib(bytes);
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
                            "حجم البيانات بعد فك الضغط كبير جدًا"
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
            if (receiverInput != null) receiverInput.close();
        } catch (Exception ignored) {
        }

        try {
            if (receiverOutput != null) receiverOutput.close();
        } catch (Exception ignored) {
        }

        try {
            if (receiverSocket != null) receiverSocket.close();
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
    // عرض النتيجة
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
            webView.removeJavascriptInterface("Captain");
            webView.destroy();
            webView = null;
        }

        super.onDestroy();
    }
}
