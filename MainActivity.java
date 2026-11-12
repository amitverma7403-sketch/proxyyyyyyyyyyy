package com.nodemax.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * The whole app is three screens:
 * 1. what NodeMax does and what it uses (the person must agree before anything else),
 * 2. sign in or create an account with a mobile number and a password,
 * 3. home: Connect or Disconnect, data shared, earnings, payout request, messages and account.
 */
public class MainActivity extends Activity {
    static final String PREFS = "node";

    // ---------------------------------------------------- crash log + diagnostics
    private static boolean crashInstalled = false;
    private static long pendingCrashStamp = 0;

    /** Saves the stack trace of any crash to a file so the next launch can show it (and the panel can too). */
    static synchronized void installCrashLog(Context ctx) {
        if (crashInstalled) {
            return;
        }
        crashInstalled = true;
        final Context app = ctx.getApplicationContext();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    StringWriter sw = new StringWriter();
                    e.printStackTrace(new PrintWriter(sw));
                    String s = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date())
                            + "  app " + appVersion(app) + "  Android " + Build.VERSION.RELEASE + "  " + Build.MODEL
                            + "  thread " + t.getName() + "\n" + sw;
                    writeText(new File(app.getFilesDir(), "crash.txt"), s.length() > 6000 ? s.substring(0, 6000) : s);
                } catch (Throwable ignored) {
                }
                if (prev != null) {
                    prev.uncaughtException(t, e);
                }
            }
        });
    }

    static String appVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    static String readText(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            try {
                byte[] b = new byte[(int) Math.min(f.length(), 40000)];
                int n = in.read(b);
                return n <= 0 ? "" : new String(b, 0, n, "UTF-8");
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    static void writeText(File f, String s) throws Exception {
        FileOutputStream o = new FileOutputStream(f);
        try {
            o.write(s.getBytes("UTF-8"));
        } finally {
            o.close();
        }
    }

    static String crashText(Context c) {
        File f = new File(c.getFilesDir(), "crash.txt");
        return f.exists() ? readText(f) : null;
    }

    /** The crash text if the panel has not been told about it yet. */
    static synchronized String pendingCrashReport(Context c) {
        File f = new File(c.getFilesDir(), "crash.txt");
        if (!f.exists()) {
            return null;
        }
        long stamp = f.lastModified();
        if (c.getSharedPreferences(PREFS, MODE_PRIVATE).getLong("crash_sent", 0) == stamp) {
            return null;
        }
        pendingCrashStamp = stamp;
        return readText(f);
    }

    static synchronized void markCrashSent(Context c) {
        if (pendingCrashStamp != 0) {
            c.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putLong("crash_sent", pendingCrashStamp).apply();
            pendingCrashStamp = 0;
        }
    }

    /** Short event log (kept to about 16 KB) that goes into the diagnostics text. */
    static synchronized void note(Context c, String msg) {
        try {
            File f = new File(c.getFilesDir(), "app.log");
            String old = f.exists() ? readText(f) : "";
            if (old == null) {
                old = "";
            }
            if (old.length() > 16000) {
                old = old.substring(old.length() - 8000);
            }
            String line = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date())
                    + " " + msg + "\n";
            writeText(f, old + line);
        } catch (Throwable ignored) {
        }
    }


    // ------------------------------------------------------------------ look
    private static final int INK = Color.parseColor("#0A0D0F");
    private static final int PANEL = Color.parseColor("#0E1316");
    private static final int RAISE = Color.parseColor("#131A1F");
    private static final int LINE = Color.parseColor("#1C2529");
    private static final int TEXT = Color.parseColor("#E6EEF1");
    private static final int MUTE = Color.parseColor("#8A9AA3");
    private static final int TEAL = Color.parseColor("#46D3FF");
    private static final int GOOD = Color.parseColor("#3ECF8E");
    private static final int WARN = Color.parseColor("#F2B84B");
    private static final int BAD = Color.parseColor("#EF6B6B");

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences sp;
    private LinearLayout page;
    private String screen = "";
    private boolean signUp = false;
    private boolean busy = false;
    private long lastMe = 0;
    private JSONObject me = null;
    private int ratePaise = 1500;

    // home screen views, filled by refresh()
    private TextView statusView, detailView, balanceView, todayView, totalView, rateView, payoutHint, warnText, acctView;
    private View statusDot, warnCard;
    private Button connectButton, payoutButton;
    private LinearLayout noticeBox;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if ("home".equals(screen)) {
                refresh();
                if (System.currentTimeMillis() - lastMe > 15000L) {
                    loadMe();
                }
            }
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
        t.setLineSpacing(dp(2), 1f);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    private View gap(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(h)));
        return v;
    }

    /** kind 0 = filled, 1 = outlined, 2 = outlined red. */
    private Button button(String s, int kind) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(s);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setStateListAnimator(null);
        b.setPadding(dp(16), 0, dp(16), 0);
        styleButton(b, kind);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    private void styleButton(Button b, int kind) {
        if (kind == 0) {
            b.setBackground(shape(TEAL, 0, 14));
            b.setTextColor(Color.parseColor("#04212B"));
        } else if (kind == 1) {
            b.setBackground(shape(RAISE, LINE, 14));
            b.setTextColor(TEXT);
        } else {
            b.setBackground(shape(Color.parseColor("#2A1618"), Color.parseColor("#5A2A2A"), 14));
            b.setTextColor(BAD);
        }
    }

    private EditText field(String hint, int inputType) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(MUTE);
        e.setTextColor(TEXT);
        e.setTextSize(16);
        e.setSingleLine(true);
        e.setInputType(inputType);
        e.setBackground(shape(INK, LINE, 12));
        e.setPadding(dp(14), dp(13), dp(14), dp(13));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        e.setLayoutParams(lp);
        return e;
    }

    private LinearLayout tile(String name, TextView value) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setBackground(shape(RAISE, LINE, 14));
        t.setPadding(dp(12), dp(10), dp(12), dp(12));
        t.addView(text(name, 12, MUTE, false));
        value.setTextSize(16);
        value.setTextColor(TEXT);
        value.setTypeface(Typeface.DEFAULT_BOLD);
        value.setPadding(0, dp(3), 0, 0);
        t.addView(value);
        return t;
    }

    private static String rupees(long paise) {
        return "₹" + String.format(Locale.US, "%.2f", paise / 100.0);
    }

    private static String data(long bytes) {
        if (bytes >= 1000000000L) {
            return String.format(Locale.US, "%.2f GB", bytes / 1e9);
        }
        return String.format(Locale.US, "%.1f MB", bytes / 1e6);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------- server calls
    interface Done {
        void ok(JSONObject r);

        void fail(int code, String msg);
    }

    /** One JSON request to the NodeMax server, off the main thread. The answer comes back on the main thread. */
    private void api(final String method, final String path, final JSONObject body, final Done cb) {
        final String token = sp.getString("mtoken", "");
        new Thread(new Runnable() {
            @Override
            public void run() {
                int code = 0;
                String msg;
                JSONObject out = null;
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(App.SERVER + path).openConnection();
                    c.setRequestMethod(method);
                    c.setConnectTimeout(12000);
                    c.setReadTimeout(25000);
                    if (token.length() > 0) {
                        c.setRequestProperty("Authorization", "Bearer " + token);
                    }
                    if (body != null) {
                        c.setDoOutput(true);
                        c.setRequestProperty("Content-Type", "application/json");
                        OutputStream os = c.getOutputStream();
                        try {
                            os.write(body.toString().getBytes("UTF-8"));
                        } finally {
                            os.close();
                        }
                    }
                    code = c.getResponseCode();
                    java.io.InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                    String raw = in == null ? "" : FrpcService.readAll(in);
                    if (code >= 400) {
                        msg = "The server answered with an error (" + code + "). Try again.";
                        try {
                            Object d = new JSONObject(raw).opt("detail");
                            if (d instanceof String) {
                                msg = (String) d;
                                msg = msg.substring(0, 1).toUpperCase(Locale.US) + msg.substring(1);
                            }
                        } catch (Exception ignored) {
                        }
                    } else {
                        msg = null;
                        out = raw.trim().startsWith("{") ? new JSONObject(raw) : new JSONObject().put("list", new JSONArray(raw));
                    }
                } catch (Exception e) {
                    msg = "Could not reach NodeMax. Check your internet and try again.";
                }
                final int fc = code;
                final String fm = msg;
                final JSONObject fo = out;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        if (fm == null) {
                            cb.ok(fo);
                        } else {
                            cb.fail(fc, fm);
                        }
                    }
                });
            }
        }, "api").start();
    }

    /** A stable id for this phone and this app only. It limits how many accounts one phone can make. */
    private String deviceUid() {
        try {
            String id = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            byte[] h = MessageDigest.getInstance("SHA-256").digest(("nodemax:" + id).getBytes("UTF-8"));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                b.append(String.format(Locale.US, "%02x", h[i]));
            }
            return b.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ start
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installCrashLog(this);
        sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        getWindow().setStatusBarColor(INK);
        getWindow().setNavigationBarColor(INK);

        final ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(INK);
        scroll.setFillViewport(true);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(18), dp(18), dp(28));
        scroll.addView(page);
        // Android 15 draws apps behind the status and navigation bars: keep the content clear of them
        scroll.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                int top, bottom;
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets s = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                    top = s.top;
                    bottom = s.bottom;
                } else {
                    top = in.getSystemWindowInsetTop();
                    bottom = in.getSystemWindowInsetBottom();
                }
                v.setPadding(0, top, 0, bottom);
                return in;
            }
        });
        setContentView(scroll);

        if (!sp.getBoolean("agreed", false)) {
            showIntro();
        } else if (sp.getString("mtoken", "").length() == 0) {
            showAuth();
        } else {
            showHome();
        }
        api("GET", "/app/api/config", null, new Done() {
            @Override
            public void ok(JSONObject r) {
                ratePaise = r.optInt("rate_paise", ratePaise);
                sp.edit().putInt("rate_paise", ratePaise).putBoolean("signup_open", r.optBoolean("signup", true)).apply();
            }

            @Override
            public void fail(int code, String msg) {
            }
        });
        ratePaise = sp.getInt("rate_paise", 1500);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(tick);
        ui.post(tick);
        if ("home".equals(screen)) {
            loadMe();
        }
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(tick);
        super.onPause();
    }

    private void header(String title, String sub) {
        page.removeAllViews();
        TextView brand = text("NodeMax", 15, TEAL, true);
        page.addView(brand);
        TextView h = text(title, 26, TEXT, true);
        h.setPadding(0, dp(6), 0, 0);
        page.addView(h);
        if (sub != null) {
            TextView s = text(sub, 15, MUTE, false);
            s.setPadding(0, dp(6), 0, 0);
            page.addView(s);
        }
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            toast("No browser found to open " + url);
        }
    }

    // ------------------------------------------------------- 1. what this app does
    private void showIntro() {
        screen = "intro";
        header("Earn from your unused internet", "Read this first. It says exactly what the app does.");
        LinearLayout c = card();
        String rate = "₹" + (ratePaise / 100);
        String[] points = {
                "When you tap Connect, NodeMax sends other customers' internet traffic through this phone. It leaves from your IP address.",
                "This uses your mobile data or Wi-Fi. You earn " + rate + " for every GB your phone carries.",
                "You are in control. Tap Disconnect at any time and nothing runs.",
                "Banking, payment and adult sites are blocked on the network.",
                "To run the service the app sends NodeMax your phone model, Android version, battery level, network type, carrier and IP address.",
                "The app does not read your contacts, messages, photos, files or location."};
        for (String p : points) {
            TextView t = text("•  " + p, 15, TEXT, false);
            t.setPadding(0, dp(7), 0, dp(7));
            c.addView(t);
        }
        page.addView(c);
        Button agree = button("I agree, continue", 0);
        agree.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sp.edit().putBoolean("agreed", true).apply();
                showAuth();
            }
        });
        page.addView(agree);
        Button pol = button("Read the privacy policy", 1);
        pol.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openUrl(App.SERVER + "/privacy");
            }
        });
        page.addView(pol);
    }

    // ------------------------------------------------------- 2. sign in / create account
    private void showAuth() {
        screen = "auth";
        header(signUp ? "Create your account" : "Sign in", signUp ? "Your mobile number is your login. No OTP needed." : "Use your mobile number and password.");
        LinearLayout c = card();
        final EditText name = field("Your name", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        final EditText phone = field("Mobile number (10 digits)", InputType.TYPE_CLASS_PHONE);
        final EditText pass = field(signUp ? "Choose a password (6 or more characters)" : "Password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (signUp) {
            c.addView(name);
        }
        c.addView(phone);
        c.addView(pass);
        final TextView err = text("", 14, BAD, false);
        err.setPadding(0, dp(10), 0, 0);
        c.addView(err);
        final Button go = button(signUp ? "Create account" : "Sign in", 0);
        c.addView(go);
        page.addView(c);
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (busy) {
                    return;
                }
                err.setText("");
                JSONObject b = new JSONObject();
                try {
                    b.put("phone", phone.getText().toString().trim());
                    b.put("password", pass.getText().toString());
                    b.put("name", name.getText().toString().trim());
                    b.put("device_uid", deviceUid());
                } catch (Exception ignored) {
                }
                busy = true;
                go.setText("Please wait...");
                api("POST", signUp ? "/app/api/signup" : "/app/api/login", b, new Done() {
                    @Override
                    public void ok(JSONObject r) {
                        busy = false;
                        sp.edit().putString("mtoken", r.optString("token")).apply();
                        showHome();
                    }

                    @Override
                    public void fail(int code, String msg) {
                        busy = false;
                        go.setText(signUp ? "Create account" : "Sign in");
                        err.setText(msg);
                    }
                });
            }
        });
        Button other = button(signUp ? "I already have an account" : "Create a new account", 1);
        other.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                signUp = !signUp;
                showAuth();
            }
        });
        if (signUp || sp.getBoolean("signup_open", true)) {
            page.addView(other);
        }
    }

    // ------------------------------------------------------- 3. home
    private void showHome() {
        screen = "home";
        header("Share and earn", null);

        // connect
        LinearLayout c = card();
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        statusDot = new View(this);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(10), dp(10));
        dl.rightMargin = dp(10);
        statusDot.setLayoutParams(dl);
        row.addView(statusDot);
        statusView = text("Not connected", 19, TEXT, true);
        row.addView(statusView);
        c.addView(row);
        detailView = text("", 14, MUTE, false);
        detailView.setPadding(0, dp(6), 0, dp(4));
        c.addView(detailView);
        connectButton = button("Connect", 0);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        bl.topMargin = dp(10);
        connectButton.setLayoutParams(bl);
        connectButton.setTextSize(17);
        connectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggle();
            }
        });
        c.addView(connectButton);
        page.addView(c);

        // something needs fixing
        LinearLayout w = card();
        w.setBackground(shape(Color.parseColor("#231C0C"), Color.parseColor("#5A4A1E"), 18));
        warnText = text("", 14, WARN, false);
        w.addView(warnText);
        Button fix = button("Open battery settings", 1);
        fix.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openBatterySettings();
            }
        });
        w.addView(fix);
        warnCard = w;
        page.addView(w);

        // earnings
        LinearLayout e = card();
        e.addView(text("Your balance", 13, MUTE, false));
        balanceView = text("₹0.00", 36, TEXT, true);
        e.addView(balanceView);
        LinearLayout tiles = new LinearLayout(this);
        tiles.setPadding(0, dp(10), 0, 0);
        todayView = new TextView(this);
        totalView = new TextView(this);
        rateView = new TextView(this);
        String[] names = {"Today", "Total shared", "You earn"};
        TextView[] vals = {todayView, totalView, rateView};
        for (int i = 0; i < 3; i++) {
            LinearLayout t = tile(names[i], vals[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) {
                lp.leftMargin = dp(8);
            }
            t.setLayoutParams(lp);
            tiles.addView(t);
        }
        e.addView(tiles);
        payoutHint = text("", 14, MUTE, false);
        payoutHint.setPadding(0, dp(12), 0, 0);
        e.addView(payoutHint);
        payoutButton = button("Request payout", 0);
        payoutButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                payoutDialog();
            }
        });
        e.addView(payoutButton);
        page.addView(e);

        // messages
        LinearLayout n = card();
        n.addView(text("Messages", 16, TEXT, true));
        noticeBox = new LinearLayout(this);
        noticeBox.setOrientation(LinearLayout.VERTICAL);
        n.addView(noticeBox);
        page.addView(n);

        // account
        LinearLayout a = card();
        a.addView(text("Account", 16, TEXT, true));
        acctView = text("", 14, MUTE, false);
        acctView.setPadding(0, dp(6), 0, 0);
        a.addView(acctView);
        Button pol = button("Privacy policy", 1);
        pol.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openUrl(App.SERVER + "/privacy");
            }
        });
        a.addView(pol);
        Button diag = button("Copy details for support", 1);
        diag.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("NodeMax details", diagnostics()));
                    toast("Copied. Paste it in your message to support.");
                }
            }
        });
        a.addView(diag);
        Button out = button("Sign out", 1);
        out.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                api("POST", "/app/api/logout", new JSONObject(), new Done() {
                    @Override
                    public void ok(JSONObject r) {
                    }

                    @Override
                    public void fail(int code, String msg) {
                    }
                });
                signOutLocal();
            }
        });
        a.addView(out);
        Button del = button("Delete my account", 2);
        del.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                deleteDialog();
            }
        });
        a.addView(del);
        page.addView(a);

        me = null;
        refresh();
        loadMe();
    }

    private boolean enabled() {
        return sp.getBoolean("enabled", false);
    }

    /** Fills the home screen from what the service and the server last said. */
    private void refresh() {
        if (!"home".equals(screen) || statusView == null) {
            return;
        }
        boolean on = enabled();
        String st = FrpcService.status;
        boolean live = on && "Connected".equals(st);
        statusView.setText(!on ? "Not connected" : live ? "Connected" : st.startsWith("Stopped") ? "Connecting..." : st);
        statusDot.setBackground(shape(live ? GOOD : on ? WARN : MUTE, 0, 5));
        detailView.setText(!on ? "Tap Connect to start sharing and earning."
                : live ? "Sharing through " + FrpcService.networkType + ", IP " + FrpcService.publicIp + ". Keep this on to earn."
                : "Trying to reach NodeMax...");
        if (!busy) {
            connectButton.setText(on ? "Disconnect" : "Connect");
            styleButton(connectButton, on ? 1 : 0);
        }
        boolean batteryFree = true;
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            batteryFree = pm == null || pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Throwable ignored) {
        }
        warnCard.setVisibility(on && !batteryFree ? View.VISIBLE : View.GONE);
        warnText.setText("Your phone may stop NodeMax when the screen is locked, and you stop earning. "
                + "Open battery settings, find NodeMax and choose \"Don't optimise\" or \"No restrictions\".");
        if (me == null) {
            return;
        }
        balanceView.setText(rupees(me.optLong("balance_paise")));
        todayView.setText(data(me.optLong("bytes_today")));
        totalView.setText(data(me.optLong("bytes_total")));
        rateView.setText("₹" + (me.optInt("rate_paise", ratePaise) / 100) + " per GB");
        JSONObject pend = me.optJSONObject("pending");
        boolean can = me.optBoolean("can_payout");
        if (pend != null) {
            payoutHint.setText(rupees(pend.optLong("amount_paise")) + " requested to " + pend.optString("upi") + ". " + me.optString("payout_note"));
            payoutHint.setTextColor(GOOD);
        } else {
            payoutHint.setText(can ? "You can ask for your payout now." : me.optString("payout_hint"));
            payoutHint.setTextColor(MUTE);
        }
        payoutButton.setVisibility(pend == null ? View.VISIBLE : View.GONE);
        payoutButton.setEnabled(can);
        payoutButton.setAlpha(can ? 1f : 0.45f);
        String nm = me.optString("name", "");
        acctView.setText((nm.length() > 0 && !"null".equals(nm) ? nm + ", " : "") + "+91 " + me.optString("phone")
                + "\nPaid so far: " + rupees(me.optLong("paid_paise")) + "\nApp version " + appVersion(this));
        noticeBox.removeAllViews();
        JSONArray ns = me.optJSONArray("notices");
        if (ns == null || ns.length() == 0) {
            TextView t = text("No messages yet.", 14, MUTE, false);
            t.setPadding(0, dp(8), 0, 0);
            noticeBox.addView(t);
        }
        for (int i = 0; ns != null && i < Math.min(5, ns.length()); i++) {
            JSONObject o = ns.optJSONObject(i);
            if (o == null) {
                continue;
            }
            TextView t = text(o.optString("title"), 15, TEXT, true);
            t.setPadding(0, dp(10), 0, 0);
            noticeBox.addView(t);
            noticeBox.addView(text(o.optString("body"), 14, MUTE, false));
        }
    }

    private void loadMe() {
        lastMe = System.currentTimeMillis();
        api("GET", "/app/api/me", null, new Done() {
            @Override
            public void ok(JSONObject r) {
                me = r;
                Push.configure(MainActivity.this, r.optJSONObject("firebase"));
                refresh();
            }

            @Override
            public void fail(int code, String msg) {
                if (code == 401 || code == 403) {
                    toast(code == 403 ? msg : "Please sign in again.");
                    signOutLocal();
                }
            }
        });
    }

    private void signOutLocal() {
        stopTunnel();
        sp.edit().remove("mtoken").remove("token").apply();
        me = null;
        signUp = false;
        showAuth();
    }

    // ------------------------------------------------------- connect / disconnect
    private void toggle() {
        if (busy) {
            return;
        }
        if (enabled()) {
            stopTunnel();
            refresh();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0
                && !sp.getBoolean("asked_notify", false)) {
            // Android shows its own question. The answer does not block connecting.
            sp.edit().putBoolean("asked_notify", true).apply();
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7);
            return;
        }
        connect();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == 7) {
            connect();
        }
    }

    private void connect() {
        busy = true;
        connectButton.setText("Connecting...");
        JSONObject b = new JSONObject();
        try {
            b.put("device_uid", deviceUid());
            b.put("model", Build.MANUFACTURER + " " + Build.MODEL);
            b.put("android", Build.VERSION.RELEASE);
        } catch (Exception ignored) {
        }
        api("POST", "/app/api/connect", b, new Done() {
            @Override
            public void ok(JSONObject r) {
                busy = false;
                String panel = r.optString("panel", "");
                sp.edit().putString("panel", panel.length() > 0 ? panel : App.SERVER).putString("token", r.optString("token"))
                        .putBoolean("enabled", true).apply();
                try {
                    Intent svc = new Intent(MainActivity.this, FrpcService.class);
                    if (Build.VERSION.SDK_INT >= 26) {
                        startForegroundService(svc);
                    } else {
                        startService(svc);
                    }
                } catch (Throwable e) {
                    note(MainActivity.this, "start service: " + e);
                    sp.edit().putBoolean("enabled", false).apply();
                    toast("Android did not let the app start sharing. Try again.");
                }
                refresh();
            }

            @Override
            public void fail(int code, String msg) {
                busy = false;
                toast(msg);
                if (code == 401) {
                    signOutLocal();
                } else {
                    refresh();
                }
            }
        });
    }

    private void stopTunnel() {
        sp.edit().putBoolean("enabled", false).apply();
        try {
            stopService(new Intent(this, FrpcService.class));
        } catch (Throwable ignored) {
        }
        FrpcService.status = "Stopped";
    }

    private void openBatterySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Throwable e) {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------- payout and account dialogs
    private LinearLayout dialogBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(6), dp(22), 0);
        return box;
    }

    private void payoutDialog() {
        if (me == null) {
            return;
        }
        LinearLayout box = dialogBox();
        box.addView(text("You are asking for " + rupees(me.optLong("balance_paise")) + ". " + me.optString("payout_note"), 14, MUTE, false));
        final EditText upi = field("UPI ID, like name@bank", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        final EditText nm = field("Name on the account (optional)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        String old = me.optString("upi", "");
        if (old.length() > 0 && !"null".equals(old)) {
            upi.setText(old);
        }
        box.addView(upi);
        box.addView(nm);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Request payout").setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Send request", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        JSONObject b = new JSONObject();
                        try {
                            b.put("upi", upi.getText().toString().trim());
                            b.put("name", nm.getText().toString().trim());
                        } catch (Exception ignored) {
                        }
                        api("POST", "/app/api/payout", b, new Done() {
                            @Override
                            public void ok(JSONObject r) {
                                toast("Request sent. " + r.optString("message"));
                                loadMe();
                            }

                            @Override
                            public void fail(int code, String msg) {
                                toast(msg);
                            }
                        });
                    }
                }).show();
    }

    private void deleteDialog() {
        LinearLayout box = dialogBox();
        box.addView(text("Your account, this phone's connection and your history are removed for good. "
                + "Any balance you have not been paid is lost, so ask for your payout first.", 14, MUTE, false));
        final EditText pw = field("Your password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(pw);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Delete my account").setView(box)
                .setNegativeButton("Keep my account", null)
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        JSONObject b = new JSONObject();
                        try {
                            b.put("password", pw.getText().toString());
                        } catch (Exception ignored) {
                        }
                        api("POST", "/app/api/delete", b, new Done() {
                            @Override
                            public void ok(JSONObject r) {
                                toast("Your account was deleted.");
                                signOutLocal();
                            }

                            @Override
                            public void fail(int code, String msg) {
                                toast(msg);
                            }
                        });
                    }
                }).show();
    }

    private String diagnostics() {
        StringBuilder b = new StringBuilder();
        b.append("NodeMax ").append(appVersion(this)).append("\n");
        b.append("Phone: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append(", Android ").append(Build.VERSION.RELEASE).append("\n");
        b.append("Sharing switched on: ").append(enabled()).append("\n");
        b.append("Status: ").append(FrpcService.status).append("\n");
        b.append("Network: ").append(FrpcService.networkType).append(", IP ").append(FrpcService.publicIp).append("\n");
        b.append("Internet check: ").append(FrpcService.selfTest).append("\n");
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            b.append("Battery optimisation off: ").append(pm.isIgnoringBatteryOptimizations(getPackageName())).append("\n");
        } catch (Throwable ignored) {
        }
        b.append("Tunnel: ").append(FrpcService.lastLog).append("\n");
        String crash = crashText(this);
        if (crash != null) {
            b.append("\nLast crash:\n").append(crash).append("\n");
        }
        String log = readText(new File(getFilesDir(), "app.log"));
        if (log != null) {
            b.append("\nLog:\n").append(log.length() > 3000 ? log.substring(log.length() - 3000) : log);
        }
        return b.toString();
    }
}
