package com.security.ravan;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import com.google.android.gms.auth.api.phone.SmsRetriever;
import com.google.android.gms.auth.api.phone.SmsRetrieverClient;
import com.google.android.gms.tasks.Task;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OtpReader extends BroadcastReceiver {

    private static final String TAG = "OtpReader";
    private static OtpCallback callback;

    public interface OtpCallback {
        void onOtpReceived(String otp);
    }

    public static void startListening(Context context, OtpCallback cb) {
        callback = cb;
        SmsRetrieverClient client = SmsRetriever.getClient(context);
        Task<Void> task = client.startSmsRetriever();
        task.addOnSuccessListener(aVoid -> {
            Log.d(TAG, "SMS Retriever started successfully");
        });
        task.addOnFailureListener(e -> {
            Log.e(TAG, "Failed to start SMS Retriever: " + e.getMessage());
        });
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (SmsRetriever.SMS_RETRIEVED_ACTION.equals(intent.getAction())) {
            Bundle extras = intent.getExtras();
            if (extras != null) {
                String message = extras.getString(SmsRetriever.EXTRA_SMS_MESSAGE);
                if (message != null) {
                    String otp = extractOtp(message);
                    if (otp != null && callback != null) {
                        callback.onOtpReceived(otp);
                    }
                }
            }
        }
    }

    private String extractOtp(String message) {
        // جستجوی اعداد 4 تا 6 رقمی
        Pattern pattern = Pattern.compile("\\b\\d{4,6}\\b");
        Matcher matcher = pattern.matcher(message);
        if (matcher.find()) {
            return matcher.group();
        }
        return null;
    }
}
