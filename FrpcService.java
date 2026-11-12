package com.nodemax.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.telephony.TelephonyManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Runs while the person has tapped Connect. It keeps this phone linked to the NodeMax network:
 * - a heartbeat every few seconds (battery, network type, carrier, public IP)
 * - keeps the bundled tunnel program (frpc) running with this phone's settings
 * - shows notifications the server sends and confirms each one
 * - checks the phone's own internet; after 3 failed checks in 2 minutes it tells the person
 * The service stops as soon as the person taps Disconnect.
 */
public class FrpcService extends Service {
    static volatile String status = "Stopped";
    static volatile String publicIp = "-";
    static volatile String networkType = "-";
    static volatile String lastLog = "";
    static volatile String selfTest = "";         // shown in the app, e.g. "5/5 OK, 310 ms"
    static volatile String selfTestJson = null;   // sent with the next heartbeat
    static volatile int hbSecs = 20;              // heartbeat interval, set by the server
    static volatile int selfSecs = 300;           // how often the phone tests its own internet
    static volatile String wakeMode = "always";   // always | charging | never
    static volatile boolean chargingNow = false;
    static volatile boolean netOk = true;         // last known real internet state
    static volatile boolean running = false;
    private static String cachedIp = null;
    private static long cachedIpAt = 0;

    private static final String CHANNEL = "node";
    private volatile boolean stopped = false;
    private Thread worker;
    private Thread watchdog;
    private Thread tester;
    private boolean alerted = false;
    private int strikes = 0;
    private Process frpc;
    private String cfgHash = "";
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifiLock;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        MainActivity.installCrashLog(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Android requires startForeground() right after startForegroundService(), even if we then stop
        try {
            startAsForeground("Starting...");
        } catch (Throwable e) {
            MainActivity.note(this, "startForeground: " + e);
            stopSelf();
            return START_NOT_STICKY;
        }
        SharedPreferences sp = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        if (!sp.getBoolean("enabled", false)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        if (worker == null || !worker.isAlive()) {
            stopped = false;
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && wake == null) {
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nodemax:tunnel");
                wake.setReferenceCounted(false);
            }
            try {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null && wifiLock == null) {
                    wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "nodemax:wifi");
                    wifiLock.setReferenceCounted(false);
                }
            } catch (Throwable ignored) {
            }
            hbSecs = sp.getInt("hb_secs", 20);
            selfSecs = sp.getInt("selftest_secs", 300);
            wakeMode = sp.getString("wake", "always");
            applyWake();
            worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    loop();
                }
            }, "node-loop");
            worker.start();
        }
        if (watchdog == null || !watchdog.isAlive()) {
            watchdog = new Thread(new Runnable() {
                @Override
                public void run() {
                    netWatchLoop();
                }
            }, "node-netwatch");
            watchdog.start();
        }
        if (tester == null || !tester.isAlive()) {
            tester = new Thread(new Runnable() {
                @Override
                public void run() {
                    selfTestLoop();
                }
            }, "node-selftest");
            tester.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopped = true;
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
        if (watchdog != null) {
            watchdog.interrupt();
        }
        if (tester != null) {
            tester.interrupt();
        }
        stopFrpc();
        try {
            if (wake != null && wake.isHeld()) {
                wake.release();
            }
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
        } catch (Throwable ignored) {
        }
        wake = null;
        wifiLock = null;
        status = "Stopped";
        super.onDestroy();
    }

    /**
     * Keeps the CPU and Wi-Fi awake with the screen off. Without this Android puts a locked, unplugged phone to
     * sleep after a while and the connection drops, so it is held the whole time unless the server says "never".
     */
    private synchronized void applyWake() {
        boolean want = !"never".equals(wakeMode);
        try {
            if (wake != null) {
                if (want && !wake.isHeld()) {
                    wake.acquire();
                } else if (!want && wake.isHeld()) {
                    wake.release();
                }
            }
            if (wifiLock != null) {
                if (want && !wifiLock.isHeld()) {
                    wifiLock.acquire();
                } else if (!want && wifiLock.isHeld()) {
                    wifiLock.release();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ main loop
    private void loop() {
        SharedPreferences sp = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        String panel = sp.getString("panel", "");
        String token = sp.getString("token", "");
        status = "Connecting...";
        while (!stopped) {
            try {
                JSONObject hb = new JSONObject(http("POST", panel + "/agent/heartbeat", token, info(sp).toString()));
                String hash = hb.getString("cfg_hash");
                if (!hash.equals(cfgHash) || !frpcAlive()) {
                    startFrpc(panel, token);
                    cfgHash = hash;
                }
                MainActivity.markCrashSent(this);  // the server has the crash report now
                hbSecs = Math.max(10, Math.min(120, hb.optInt("hb_secs", hbSecs)));
                selfSecs = Math.max(60, Math.min(3600, hb.optInt("selftest_secs", selfSecs)));
                wakeMode = hb.optString("wake", wakeMode);
                sp.edit().putInt("hb_secs", hbSecs).putInt("selftest_secs", selfSecs).putString("wake", wakeMode).apply();
                applyWake();
                JSONArray cmds = hb.optJSONArray("commands");
                for (int i = 0; cmds != null && i < cmds.length(); i++) {
                    // this app has no remote controls: say so, so the server does not keep asking
                    sendAck(panel, token, cmds.getJSONObject(i).getInt("id"), false, "not supported by this app");
                }
                JSONArray notices = hb.optJSONArray("notices");
                if (notices != null && notices.length() > 0) {
                    JSONArray ids = new JSONArray();
                    for (int i = 0; i < notices.length(); i++) {
                        JSONObject n = notices.getJSONObject(i);
                        showNotice(this, 1000 + n.getInt("id"), n.optString("title", "NodeMax"), n.optString("body", ""));
                        ids.put(n.getInt("id"));
                    }
                    try {
                        http("POST", panel + "/agent/notice_ack", token, new JSONObject().put("ids", ids).toString());
                    } catch (Exception e) {
                        MainActivity.note(this, "notice ack: " + e);
                    }
                }
                status = frpcAlive() ? "Connected" : "Tunnel program not running";
                updateNotification(frpcAlive() ? "Connected. You are earning while this is on." : status);
            } catch (Exception e) {
                String m = String.valueOf(e.getMessage());
                if (m.contains("HTTP 401")) {  // the phone was removed on the server: stop cleanly, the app asks to connect again
                    status = "Disconnected by the server. Open the app and tap Connect.";
                    sp.edit().putBoolean("enabled", false).apply();
                    updateNotification(status);
                    stopSelf();
                    return;
                }
                status = netOk ? "Reconnecting..." : "No internet";
                MainActivity.note(this, "heartbeat: " + e);
                updateNotification(status);
            }
            for (int i = 0; i < hbSecs && !stopped; i++) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    // ----------------------------------------------------------------- frpc
    private boolean frpcAlive() {
        if (frpc == null) {
            return false;
        }
        try {
            frpc.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    private void startFrpc(String panel, String token) throws Exception {
        stopFrpc();
        String toml = http("GET", panel + "/agent/frpc.toml", token, null);
        File cfg = new File(getFilesDir(), "frpc.toml");
        cfg.setReadable(false, false);  // holds the device token: owner only
        cfg.setReadable(true, true);
        FileOutputStream fo = new FileOutputStream(cfg);
        try {
            fo.write(toml.getBytes("UTF-8"));
        } finally {
            fo.close();
        }
        File bin = new File(getApplicationInfo().nativeLibraryDir, "libfrpc.so");
        if (!bin.exists()) {
            throw new Exception("tunnel program missing for this phone's CPU");
        }
        // Every proxied connection costs the tunnel program two file handles, and some phones start apps with a low
        // limit (1024). Raise it to the most the phone allows before starting; fall back to a plain start if there is no shell.
        ProcessBuilder pb;
        if (new File("/system/bin/sh").exists()) {
            pb = new ProcessBuilder("/system/bin/sh", "-c",
                    "ulimit -n $(ulimit -Hn) 2>/dev/null; exec \"$0\" \"$@\"",
                    bin.getAbsolutePath(), "-c", cfg.getAbsolutePath());
        } else {
            pb = new ProcessBuilder(bin.getAbsolutePath(), "-c", cfg.getAbsolutePath());
        }
        pb.directory(getFilesDir());
        pb.redirectErrorStream(true);
        final Process p = pb.start();
        frpc = p;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                    String line;
                    while ((line = r.readLine()) != null) {
                        lastLog = line;
                    }
                } catch (Exception ignored) {
                }
            }
        }, "frpc-log").start();
    }

    private void stopFrpc() {
        if (frpc != null) {
            frpc.destroy();
            frpc = null;
        }
    }

    private void sendAck(String panel, String token, int id, boolean ok, String result) {
        try {
            JSONObject body = new JSONObject();
            body.put("id", id);
            body.put("ok", ok);
            body.put("result", result);
            http("POST", panel + "/agent/ack", token, body.toString());
        } catch (Exception ignored) {
        }
    }

    // --------------------------------------------- internet on but not working
    /**
     * Every 40 seconds, while the phone says it has a network, make one tiny real request.
     * Three failures in a row (about two minutes) means the internet is on but not working:
     * the person gets a notification, once per incident.
     */
    private void netWatchLoop() {
        while (!stopped) {
            try {
                Thread.sleep(40000L);
            } catch (InterruptedException e) {
                return;
            }
            try {
                if (!hasNetwork()) {       // no network at all (flight mode, no signal): Android already shows that
                    netOk = false;
                    strikes = 0;
                    continue;
                }
                if (internetOk()) {
                    if (alerted) {
                        cancelNotice(2);
                    }
                    netOk = true;
                    alerted = false;
                    strikes = 0;
                    continue;
                }
                netOk = false;
                strikes++;
                if (strikes >= 3 && !alerted) {
                    alerted = true;
                    alertNoInternet();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private boolean hasNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Throwable e) {
            return true;
        }
    }

    /** Real internet check: any one of two small requests working is enough. */
    private boolean internetOk() {
        String[] urls = {"https://connectivitycheck.gstatic.com/generate_204", "https://www.google.com/generate_204"};
        for (String u : urls) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                c.setUseCaches(false);
                c.setInstanceFollowRedirects(false);
                int code = c.getResponseCode();
                c.disconnect();
                if (code >= 200 && code < 400) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private void alertNoInternet() {
        MainActivity.note(this, "internet on but not working: 3 checks failed");
        showNotice(this, 2, "Internet is not working",
                "Your phone is connected to a network but the internet did not work in 3 checks. "
                        + "Check mobile data, signal or your recharge. You earn only while it works.");
    }

    private void cancelNotice(int id) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(id);
        }
    }

    /** Shows a message from NodeMax. Used for server notifications, push messages and the internet warning. */
    static void showNotice(Context ctx, int id, String title, String body) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            Intent open = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(ctx, id, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel("alert", "Messages from NodeMax", NotificationManager.IMPORTANCE_HIGH));
                b = new Notification.Builder(ctx, "alert");
            } else {
                b = new Notification.Builder(ctx);
            }
            nm.notify(id, b.setContentTitle(title)
                    .setContentText(body)
                    .setStyle(new Notification.BigTextStyle().bigText(body))
                    .setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build());
        } catch (Throwable ignored) {
        }
    }

    // ----------------------------------------------------------------- info
    private JSONObject info(SharedPreferences sp) throws Exception {
        JSONObject o = new JSONObject();
        long nowMs = System.currentTimeMillis();
        if (cachedIp == null || nowMs - cachedIpAt > 120000L) {  // one lookup per 2 minutes: fewer radio wake-ups
            String fresh = fetchPublicIp();
            if (fresh != null) {
                cachedIp = fresh;
                cachedIpAt = nowMs;
            }
        }
        String ip = cachedIp;
        if (ip != null) {
            publicIp = ip;
        }
        o.put("ip", ip);
        o.put("app_ver", MainActivity.appVersion(this));
        if (selfTestJson != null) {
            o.put("selftest", new JSONObject(selfTestJson));
        }
        String crash = MainActivity.pendingCrashReport(this);
        if (crash != null) {
            o.put("crash", crash);
        }
        String fcm = sp.getString("fcm_token", "");
        if (fcm.length() > 0) {
            o.put("fcm", fcm);
        }
        o.put("model", Build.MANUFACTURER + " " + Build.MODEL);
        o.put("android", Build.VERSION.RELEASE);
        String carrier = "";
        TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        if (tm != null) {
            carrier = tm.getNetworkOperatorName();
        }
        o.put("carrier", carrier);
        networkType = detectNetworkType();
        o.put("network_type", networkType);
        o.put("net_gen", netGen());
        int dbm = signalDbm();
        if (dbm != 0) {
            o.put("signal_dbm", dbm);
        }
        int pct = -1;
        try {
            Intent bat = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (bat != null) {
                int level = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                if (level >= 0 && scale > 0) {
                    pct = level * 100 / scale;
                }
                int st = bat.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                chargingNow = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
                o.put("charging", chargingNow);
            }
        } catch (Exception e) {
            MainActivity.note(this, "battery intent: " + e);
        }
        if (pct < 0) {  // second source if the broadcast gives nothing
            try {
                BatteryManager bm = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
                if (bm != null) {
                    pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                    if (pct <= 0 || pct > 100) {
                        pct = -1;
                    }
                }
            } catch (Exception e) {
                MainActivity.note(this, "battery property: " + e);
            }
        }
        if (pct >= 0) {
            o.put("battery", pct);
        }
        o.put("data_enabled", netOk);
        o.put("can_control", false);
        return o;
    }

    /** 5G / 4G / 3G / 2G / WIFI, or MOBILE when the phone will not say (Android 12 and older). */
    private String netGen() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (caps == null) {
                return "none";
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return "WIFI";
            }
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                return "OTHER";
            }
        } catch (Throwable e) {
            return "";
        }
        try {
            // Android 13+ answers this with the basic phone state permission, which needs no prompt
            TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            switch (tm.getDataNetworkType()) {
                case 20:
                    return "5G";
                case 13:
                case 19:
                    return "4G";
                case 3: case 5: case 6: case 8: case 9: case 10: case 12: case 14: case 15: case 17:
                    return "3G";
                case 1: case 2: case 4: case 7: case 11: case 16:
                    return "2G";
                default:
                    return "MOBILE";
            }
        } catch (Throwable e) {
            return "MOBILE";
        }
    }

    /** Mobile signal in dBm, or 0 when unknown. */
    private int signalDbm() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    int v = caps.getSignalStrength();
                    if (v != Integer.MIN_VALUE && v < 0 && v > -150) {
                        return v;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private String detectNetworkType() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (caps == null) {
                return "none";
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return "wifi";
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                return "mobile";
            }
        } catch (Throwable ignored) {
        }
        return "unknown";
    }

    private static String fetchPublicIp() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("https://api.ipify.org").openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            String s = readAll(c.getInputStream()).trim();
            return s.length() > 64 ? s.substring(0, 64) : s;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------- regular internet test
    private void selfTestLoop() {
        try {
            Thread.sleep(8000);
        } catch (InterruptedException e) {
            return;
        }
        while (!stopped) {
            try {
                runSelfTest();
            } catch (Exception e) {
                MainActivity.note(this, "selftest: " + e);
            }
            for (int i = 0; i < selfSecs && !stopped; i++) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    /** 5 requests at once (one of them by IP so DNS problems show up separately). */
    private void runSelfTest() throws Exception {
        final String[] urls = {
                "https://www.google.com/generate_204",
                "https://connectivitycheck.gstatic.com/generate_204",
                "https://www.cloudflare.com/cdn-cgi/trace",
                "https://1.1.1.1/cdn-cgi/trace",
                "https://www.gstatic.com/generate_204"};
        final long[] ms = new long[urls.length];
        final String[] err = new String[urls.length];
        Thread[] ts = new Thread[urls.length];
        for (int i = 0; i < urls.length; i++) {
            final int n = i;
            ts[i] = new Thread(new Runnable() {
                @Override
                public void run() {
                    long t0 = System.currentTimeMillis();
                    try {
                        HttpURLConnection c = (HttpURLConnection) new URL(urls[n]).openConnection();
                        c.setConnectTimeout(7000);
                        c.setReadTimeout(7000);
                        c.setUseCaches(false);
                        c.setInstanceFollowRedirects(false);
                        int code = c.getResponseCode();
                        c.disconnect();
                        if (code >= 200 && code < 400) {
                            ms[n] = System.currentTimeMillis() - t0;
                        } else {
                            err[n] = "HTTP " + code;
                        }
                    } catch (Exception e) {
                        err[n] = e.getClass().getSimpleName();
                    }
                }
            });
            ts[i].start();
        }
        for (Thread t : ts) {
            t.join(15000);
        }
        int ok = 0;
        java.util.ArrayList<Long> good = new java.util.ArrayList<Long>();
        String firstErr = null;
        for (int i = 0; i < urls.length; i++) {
            if (ms[i] > 0) {
                ok++;
                good.add(ms[i]);
            } else if (firstErr == null) {
                firstErr = Uri.parse(urls[i]).getHost() + ": " + (err[i] == null ? "timeout" : err[i]);
            }
        }
        java.util.Collections.sort(good);
        long med = good.isEmpty() ? 0 : good.get(good.size() / 2);
        JSONObject o = new JSONObject();
        o.put("ok", ok);
        o.put("n", urls.length);
        o.put("ms", med);
        if (firstErr != null && ok < urls.length) {
            o.put("err", firstErr);
        }
        selfTestJson = o.toString();
        if (ok > 0) {
            netOk = true;
        }
        selfTest = ok + "/" + urls.length + (ok > 0 ? " OK, " + med + " ms" : " - no internet");
    }

    // ----------------------------------------------------------------- http
    static String http(String method, String url, String token, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        if (token != null && token.length() > 0) {
            c.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream os = c.getOutputStream();
            try {
                os.write(body.getBytes("UTF-8"));
            } finally {
                os.close();
            }
        }
        int code = c.getResponseCode();
        if (code >= 400) {
            throw new Exception("server answered HTTP " + code);
        }
        return readAll(c.getInputStream());
    }

    static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        try {
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } finally {
            in.close();
        }
        return out.toString("UTF-8");
    }

    // --------------------------------------------------------- notification
    private void startAsForeground(String text) {
        Notification n = buildNotification(text);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(1, n);
        }
    }

    private Notification buildNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            if (nm != null) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Sharing status", NotificationManager.IMPORTANCE_LOW));
            }
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE);
        return b.setContentTitle("NodeMax is sharing your internet")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(1, buildNotification(text));
        }
    }
}
