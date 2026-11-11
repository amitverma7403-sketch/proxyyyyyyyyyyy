package com.nodeconsole.node;

import android.Manifest;
import android.app.Activity;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

import org.json.JSONObject;

/** Scan the pairing QR from your dashboard (or paste the code), then tap Connect. */
public class MainActivity extends Activity {
    static final String PREFS = "node";

    // Same palette as the web dashboard
    private static final int INK = Color.parseColor("#0A0D0F");
    private static final int PANEL = Color.parseColor("#0E1316");
    private static final int RAISE = Color.parseColor("#131A1F");
    private static final int LINE = Color.parseColor("#1C2529");
    private static final int TEXT = Color.parseColor("#E6EEF1");
    private static final int MUTE = Color.parseColor("#8A9AA3");
    private static final int TEAL = Color.parseColor("#46D3FF");
    private static final int TEAL_D = Color.parseColor("#0D2A35");
    private static final int WARN = Color.parseColor("#F2B84B");
    private static final int BAD = Color.parseColor("#EF6B6B");

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView statusView, detailView, warnView, ipView, netView, pairedView;
    private View statusDot, warnCard, codeBox;
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

    private GradientDrawable shape(int fill, int stroke, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) {
            g.setStroke(dp(1), stroke);
        }
        return g;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(shape(PANEL, LINE, 18));
        c.setPadding(dp(18), dp(16), dp(18), dp(18));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        c.setLayoutParams(lp);
        return c;
    }

    private TextView text(String s, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s.toUpperCase(), 11, MUTE, true);
        t.setLetterSpacing(0.09f);
        return t;
    }

    /** Filled teal, outlined, or outlined-red button. */
    private Button button(String s, int kind) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(s);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setStateListAnimator(null);
        b.setPadding(dp(16), 0, dp(16), 0);
        styleButton(b, kind);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    private void styleButton(Button b, int kind) {
        if (kind == 0) {            // primary
            b.setBackground(shape(TEAL, 0, 14));
            b.setTextColor(Color.parseColor("#04212B"));
        } else if (kind == 1) {     // secondary
            b.setBackground(shape(RAISE, LINE, 14));
            b.setTextColor(TEXT);
        } else {                    // danger
            b.setBackground(shape(Color.parseColor("#2A1618"), Color.parseColor("#5A2A2A"), 14));
            b.setTextColor(BAD);
        }
    }

    private LinearLayout tile(String name, TextView value) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setBackground(shape(RAISE, LINE, 14));
        t.setPadding(dp(14), dp(12), dp(14), dp(12));
        t.addView(label(name));
        value.setTextSize(17);
        value.setTextColor(TEXT);
        value.setTypeface(Typeface.DEFAULT_BOLD);
        value.setPadding(0, dp(4), 0, 0);
        value.setSingleLine(true);
        t.addView(value);
        return t;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window w = getWindow();
        w.setStatusBarColor(INK);
        w.setNavigationBarColor(INK);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(INK);
        root.setPadding(dp(18), dp(28), dp(18), dp(28));

        // ---- header
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.addView(text("Node Console", 26, TEXT, true));
        TextView sub = text("Share this phone's mobile connection with your gateway.", 13, MUTE, false);
        sub.setPadding(0, dp(2), 0, dp(6));
        head.addView(sub);
        root.addView(head);

        // ---- status card
        LinearLayout status = card();
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusDot = new View(this);
        statusDot.setLayoutParams(new LinearLayout.LayoutParams(dp(12), dp(12)));
        statusRow.addView(statusDot);
        statusView = text("", 22, TEXT, true);
        statusView.setPadding(dp(10), 0, 0, 0);
        statusRow.addView(statusView);
        status.addView(statusRow);

        detailView = text("", 13, MUTE, false);
        detailView.setPadding(0, dp(6), 0, dp(12));
        status.addView(detailView);

        LinearLayout tiles = new LinearLayout(this);
        tiles.setOrientation(LinearLayout.HORIZONTAL);
        ipView = new TextView(this);
        netView = new TextView(this);
        LinearLayout t1 = tile("Public IP", ipView);
        LinearLayout t2 = tile("Network", netView);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        half.rightMargin = dp(5);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        half2.leftMargin = dp(5);
        tiles.addView(t1, half);
        tiles.addView(t2, half2);
        status.addView(tiles);

        connectButton = button("Connect", 0);
        connectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggle();
            }
        });
        status.addView(connectButton);
        root.addView(status);

        // ---- wi-fi warning
        warnCard = card();
        warnCard.setBackground(shape(Color.parseColor("#2B2411"), Color.parseColor("#5B4A1B"), 14));
        warnView = text("", 13, WARN, false);
        ((LinearLayout) warnCard).addView(warnView);
        warnCard.setVisibility(View.GONE);
        root.addView(warnCard);

        // ---- pairing card
        LinearLayout pair = card();
        pair.addView(text("Pair this phone", 17, TEXT, true));
        pairedView = text("", 13, MUTE, false);
        pairedView.setPadding(0, dp(2), 0, 0);
        pair.addView(pairedView);

        Button scan = button("Scan QR code", 0);
        scan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!has(Manifest.permission.CAMERA) && Build.VERSION.SDK_INT >= 23) {
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, 2);
                } else {
                    scanQr();
                }
            }
        });
        pair.addView(scan);

        Button cam = button("Scan with the camera app", 1);
        cam.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openCameraApp();
            }
        });
        pair.addView(cam);

        Button paste = button("Paste code instead", 1);
        paste.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                codeBox.setVisibility(View.VISIBLE);
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                    CharSequence t = cm.getPrimaryClip().getItemAt(0).getText();
                    if (t != null && t.toString().trim().startsWith("NC1.")) {
                        codeInput.setText(t.toString().trim());
                    }
                }
            }
        });
        pair.addView(paste);

        codeBox = new LinearLayout(this);
        ((LinearLayout) codeBox).setOrientation(LinearLayout.VERTICAL);
        codeInput = new EditText(this);
        codeInput.setTextColor(TEXT);
        codeInput.setHintTextColor(MUTE);
        codeInput.setHint("NC1....");
        codeInput.setTextSize(12);
        codeInput.setTypeface(Typeface.MONOSPACE);
        codeInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        codeInput.setMinLines(3);
        codeInput.setGravity(Gravity.TOP);
        codeInput.setBackground(shape(INK, LINE, 12));
        codeInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(10);
        ((LinearLayout) codeBox).addView(codeInput, clp);
        Button use = button("Use this code", 1);
        use.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                savePairing(codeInput.getText().toString().trim(), false);
            }
        });
        ((LinearLayout) codeBox).addView(use);
        codeBox.setVisibility(View.GONE);
        pair.addView(codeBox);
        root.addView(pair);

        // ---- keep alive
        LinearLayout keep = card();
        keep.addView(text("Keep it running", 17, TEXT, true));
        TextView tip = text("Turn Wi-Fi off so traffic uses mobile data. Keep the phone charging and let this app run in the background.", 13, MUTE, false);
        tip.setPadding(0, dp(4), 0, 0);
        keep.addView(tip);
        Button battery = button("Allow background running", 1);
        battery.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askBatteryExemption();
            }
        });
        keep.addView(battery);
        root.addView(keep);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(INK);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);

        askAllPermissions();

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (sp.getString("code", "").length() > 0) {
            codeInput.setText(sp.getString("code", ""));
        }
        handleIntent(getIntent());
    }

    private boolean has(String perm) {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    /** Camera (for scanning) and notifications, then the battery exemption that keeps the tunnel alive. */
    private void askAllPermissions() {
        java.util.ArrayList<String> need = new java.util.ArrayList<String>();
        if (!has(Manifest.permission.CAMERA)) {
            need.add(Manifest.permission.CAMERA);
        }
        if (Build.VERSION.SDK_INT >= 33 && !has("android.permission.POST_NOTIFICATIONS")) {
            need.add("android.permission.POST_NOTIFICATIONS");
        }
        if (Build.VERSION.SDK_INT >= 23 && !need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), 1);
        } else {
            askBatteryOnce();
        }
    }

    private void askBatteryOnce() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!sp.getBoolean("asked_battery", false)) {
            sp.edit().putBoolean("asked_battery", true).apply();
            askBatteryExemption();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == 2) {  // tapped Scan without camera permission
            if (has(Manifest.permission.CAMERA)) {
                scanQr();
            } else {
                Toast.makeText(this, "Camera permission is off. Use the camera app or paste the code instead.", Toast.LENGTH_LONG).show();
            }
        } else {
            askBatteryOnce();
        }
    }

    /** Open the phone's own camera: scanning the dashboard QR there opens this app by itself. */
    private void openCameraApp() {
        try {
            startActivity(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA));
            Toast.makeText(this, "Point the camera at the QR, then tap the link that appears", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "No camera app found. Paste the code instead.", Toast.LENGTH_LONG).show();
        }
    }

    private void handleIntent(Intent in) {
        if (in == null || in.getData() == null) {
            return;
        }
        Uri u = in.getData();
        if ("nodeconsole".equals(u.getScheme()) && u.getQueryParameter("c") != null) {
            in.setData(null);
            savePairing(u.getQueryParameter("c"), true);
        }
    }

    @Override
    protected void onNewIntent(Intent in) {
        super.onNewIntent(in);
        setIntent(in);
        handleIntent(in);
    }

    /** Google's code scanner runs inside Play Services: no camera permission or extra screen of ours. */
    private void scanQr() {
        try {
            GmsBarcodeScannerOptions opts = new GmsBarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE).build();
            GmsBarcodeScanning.getClient(this, opts).startScan()
                    .addOnSuccessListener(barcode -> {
                        String v = barcode.getRawValue();
                        if (v != null) {
                            savePairing(v.trim(), true);
                        }
                    })
                    .addOnFailureListener(e -> scannerUnavailable(e));
        } catch (Throwable t) {
            scannerUnavailable(t);
        }
    }

    private void scannerUnavailable(Throwable e) {
        codeBox.setVisibility(View.VISIBLE);
        String why = e == null || e.getMessage() == null ? "" : " (" + e.getMessage() + ")";
        Toast.makeText(this, "Scanner failed" + why + ". Use the camera app or paste the code.", Toast.LENGTH_LONG).show();
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

    private boolean mobileDataOn() {
        try {
            return Settings.Global.getInt(getContentResolver(), "mobile_data", 1) == 1;
        } catch (Exception e) {
            return true;
        }
    }

    private boolean enabled() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("enabled", false);
    }

    /** Validate and store a pairing code; optionally connect straight away (after a scan). */
    private void savePairing(String code, boolean connectNow) {
        try {
            if (code.startsWith("nodeconsole://")) {
                code = Uri.parse(code).getQueryParameter("c");
            }
            JSONObject o = decode(code);
            String panel = o.getString("p");
            String token = o.getString("t");
            boolean was = enabled();
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("code", code).putString("panel", panel).putString("token", token).apply();
            codeInput.setText(code);
            Toast.makeText(this, "Phone paired", Toast.LENGTH_SHORT).show();
            if (was) {
                stopService(new Intent(this, FrpcService.class));  // restart with the new pairing
            }
            if (connectNow || was) {
                startTunnel();
            }
        } catch (Exception e) {
            Toast.makeText(this, "That is not a pairing code from your dashboard", Toast.LENGTH_LONG).show();
        }
        refresh();
    }

    private void startTunnel() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("enabled", true).apply();
        Intent svc = new Intent(this, FrpcService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
    }

    private void toggle() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (enabled()) {
            sp.edit().putBoolean("enabled", false).apply();
            stopService(new Intent(this, FrpcService.class));
            refresh();
            return;
        }
        if (sp.getString("token", "").length() == 0) {
            String typed = codeInput.getText().toString().trim();
            if (typed.length() == 0) {
                Toast.makeText(this, "Scan the QR code first", Toast.LENGTH_LONG).show();
                return;
            }
            savePairing(typed, true);
            return;
        }
        startTunnel();
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
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean paired = sp.getString("token", "").length() > 0;
        boolean on = enabled();
        boolean good = on && "Connected".equals(FrpcService.status);

        connectButton.setText(on ? "Disconnect" : "Connect");
        styleButton(connectButton, on ? 2 : 0);

        statusView.setText(on ? FrpcService.status : "Not connected");
        statusView.setTextColor(good ? TEAL : on ? WARN : TEXT);
        statusDot.setBackground(shape(good ? TEAL : on ? WARN : MUTE, 0, 6));

        if (on) {
            detailView.setText((FrpcService.lastLog.length() > 0 ? "Tunnel: " + FrpcService.lastLog : "Starting the tunnel...")
                    + "\nRoot: " + (FrpcService.rooted ? "yes, remote IP rotation and data control are on"
                    : "no, remote IP rotation and data control are off"));
        } else {
            detailView.setText(paired ? "Ready. Tap Connect to go online." : "Scan the QR code from your dashboard to get started.");
        }
        ipView.setText(on && FrpcService.publicIp != null && FrpcService.publicIp.length() > 0 ? FrpcService.publicIp : "-");
        netView.setText(on && FrpcService.networkType != null && FrpcService.networkType.length() > 0 ? FrpcService.networkType : "-");

        boolean wifi = on && "wifi".equals(FrpcService.networkType);
        boolean dataOff = on && !mobileDataOn();
        warnCard.setVisibility(wifi || dataOff ? View.VISIBLE : View.GONE);
        warnView.setText(dataOff ? "Mobile data is off, so this phone cannot carry traffic. Turn it on in Settings."
                : "This phone is on Wi-Fi, so traffic exits through Wi-Fi. Turn Wi-Fi off to use mobile data.");

        pairedView.setText(paired ? "Paired. Scan a new code to switch to a different device." : "Scan the QR code shown when you add a device.");
    }
}
