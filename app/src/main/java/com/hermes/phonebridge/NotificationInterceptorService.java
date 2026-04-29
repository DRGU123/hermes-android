package com.hermes.phonebridge;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.os.Bundle;
import android.util.Log;

public class NotificationInterceptorService extends NotificationListenerService {

    private static final String TAG = "NotificationInterceptor";

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        String text = "";
        CharSequence ticker = sbn.getNotification().tickerText;
        Bundle extras = sbn.getNotification().extras;
        if (extras != null) {
            CharSequence title = extras.getCharSequence("android.title");
            CharSequence body = extras.getCharSequence("android.text");
            text = (title != null ? title : "") + " | " + (body != null ? body : "");
        }

        Log.i(TAG, "Notification:[" + pkg + "] " + text);

        // Relay to Hermes if PhoneBridge service is active
        if (PhoneBridgeService.getInstance() != null) {
            // Could add a notification queue or broadcast here
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // Optionally handle dismissed notifications
    }
}
