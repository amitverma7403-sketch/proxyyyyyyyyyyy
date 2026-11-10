package com.nodeconsole.node;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/** One screen: paste the pairing code from your dashboard, tap Connect. */
public class MainActivity extends Activity {
    static final String PREFS = "node";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView statusView;
    private TextView detailView;
    private TextView warnView;
    private EditText codeInput;
    private Button connectButton;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, 2000);
        }
    };

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int bg = Color.parseColor("#0E1214");
        int fg = Color.parseColor("#E8EEF0");
        int mute = Color.parseColor("#8A9AA1");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        root.setPadding(dp(20), dp(40), dp(20), dp(24));

        TextView title = new TextView(this);
        title.setText("Node Console");
        title.setTextColor(fg);
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setTextSize(18);
        statusView.setPadding(0, dp(16), 0, dp(4));
        root.addView(statusView);

        detailView = new TextView(this);
        detailView.setTextColor(mute);
        detailView.setTextSize(14);
        root.addView(detailView);

        warnView = new TextView(this);
        warnView.setTextColor(Color.parseColor("#F2B84B"));
        warnView.setTextSize(14);
        warnView.setPadding(0, dp(10), 0, 0);
        root.addView(warnView);

        TextView label = new TextView(this);
        label.setText("Pairing code (from your dashboard)");
        label.setTextColor(mute);
        label.setTextSize(13);
        label.setPadding(0, dp(24), 0, dp(6));
        root.addView(label);

        codeInput = new EditText(this);
        codeInput.setTextColor(fg);
        codeInput.setHintTextColor(mute);
        codeInput.setHint("NC1....");
        codeInput.setTextSize(13);
        codeInput.setMinLines(3);
        codeInput.setGravity(Gravity.TOP);
        codeInput.setBackgroundColor(Color.parseColor("#161C1F"));
        codeInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(codeInput);

        Button paste = new Button(this);
        paste.setText("Paste from clipboard");
        paste.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                    CharSequence t = cm.getPrimaryClip().getItemAt(0).getText();
                    if (t != null) {
                        codeInput.setText(t.toString().trim());
                    }
                }
            }
        });
        root.addView(paste);

        connectButton = new Button(this);
        connectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggle();
            }
        });
        root.addView(connectButton);

        Button battery = new Button(this);
        battery.setText("Allow running in background");
        battery.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askBatteryExemption();
            }
        });
        root.addView(battery);

        TextView tip = new TextView(this);
        tip.setText("Tip: turn Wi-Fi off so traffic uses your mobile data. Keep the phone charging and keep this app out of battery-saver lists.");
        tip.setTextColor(mute);
        tip.setTextSize(13);
        tip.setPadding(0, dp(20), 0, 0);
        root.addView(tip);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(bg);
        scroll.addView(root);
        setContentView(scroll);

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (sp.getString("code", "").length() > 0) {
            codeInput.setText(sp.getString("code", ""));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(tick);
        super.onPause();
    }

    private boolean enabled() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("enabled", false);
    }

    private void toggle() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (enabled()) {
            sp.edit().putBoolean("enabled", false).apply();
            stopService(new Intent(this, FrpcService.class));
            refresh();
            return;
        }
        String code = codeInput.getText().toString().trim();
        try {
            JSONObject o = decode(code);
            String panel = o.getString("p");
            String token = o.getString("t");
            sp.edit().putString("code", code).putString("panel", panel).putString("token", token)
                    .putBoolean("enabled", true).apply();
        } catch (Exception e) {
            Toast.makeText(this, "That pairing code is not valid", Toast.LENGTH_LONG).show();
            return;
        }
        Intent svc = new Intent(this, FrpcService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
        refresh();
    }

    private static JSONObject decode(String code) throws Exception {
        String s = code.trim();
        if (s.startsWith("NC1.")) {
            s = s.substring(4);
        }
        while (s.length() % 4 != 0) {
            s = s + "=";
        }
        byte[] raw = Base64.decode(s, Base64.URL_SAFE);
        return new JSONObject(new String(raw, "UTF-8"));
    }

    private void askBatteryExemption() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Toast.makeText(this, "Already allowed", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private void refresh() {
        boolean on = enabled();
        connectButton.setText(on ? "Disconnect" : "Connect");
        String st = on ? FrpcService.status : "Not connected";
        statusView.setText(st);
        boolean good = on && "Connected".equals(FrpcService.status);
        statusView.setTextColor(Color.parseColor(good ? "#3ECFB0" : on ? "#F2B84B" : "#8A9AA1"));
        if (on) {
            detailView.setText("Public IP: " + FrpcService.publicIp + "\nNetwork: " + FrpcService.networkType
                    + (FrpcService.lastLog.length() > 0 ? "\nTunnel: " + FrpcService.lastLog : ""));
        } else {
            detailView.setText("Paste your pairing code and tap Connect.");
        }
        warnView.setText(on && "wifi".equals(FrpcService.networkType)
                ? "This phone is on Wi-Fi, so traffic exits through Wi-Fi. Turn Wi-Fi off to use mobile data." : "");
    }
}
