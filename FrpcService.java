package com.nodeconsole.node;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
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
 * Keeps this phone connected to your panel:
 * - heartbeat every 15s (public IP, carrier, network type)
 * - downloads this device's frpc config and keeps the bundled frpc running
 * - handles "rotate", "data_off" and "data_on" commands (need root for now)
 * - reports battery level and whether mobile data is on
 * - every 2 minutes tests the phone's own internet with 5 requests and reports the result
 * - watchdog: if mobile data stays off for a few minutes, switches it back on
 */
public class FrpcService extends Service {
    static volatile String status = "Stopped";
    static volatile String publicIp = "-";
    static volatile String networkType = "-";
    static volatile String lastLog = "";
    static volatile boolean rooted = false;
    static volatile String selfTest = "";      // shown in the app, e.g. "5/5 OK, 310 ms"
    static volatile String selfTestJson = null;  // sent with the next heartbeat
    private static String cachedIp = null;
    private static long cachedIpAt = 0;
    private static long rootCheckedAt = 0;

    private static final String CHANNEL = "node";
    private volatile boolean stopped = false;
    private Thread worker;
    private Thread watchdog;
    private Thread tester;
    private boolean alerted = false;
    private Process frpc;
    private String cfgHash = "";
    private PowerManager.WakeLock wake;

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
        startAsForeground("Starting...");
        SharedPreferences sp = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        if (!sp.getBoolean("enabled", false)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (worker == null || !worker.isAlive()) {
            stopped = false;
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && wake == null) {
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nodeconsole:tunnel");
                wake.acquire();
            }
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
                    watchLoop();
                }
            }, "node-watchdog");
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
        if (wake != null && wake.isHeld()) {
            wake.release();
        }
        wake = null;
        status = "Stopped";
        super.onDestroy();
    }

    // ------------------------------------------------------------ main loop
    private void loop() {
        SharedPreferences sp = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        String panel = sp.getString("panel", "");
        String token = sp.getString("token", "");
        status = "Connecting...";
        while (!stopped) {
            try {
                JSONObject hb = new JSONObject(http("POST", panel + "/agent/heartbeat", token, info().toString()));
                String hash = hb.getString("cfg_hash");
                if (!hash.equals(cfgHash) || !frpcAlive()) {
                    startFrpc(panel, token);
                    cfgHash = hash;
                }
                MainActivity.markCrashSent(this);  // the panel has the crash report now
                sp.edit().putInt("auto_on_min", hb.optInt("auto_on_min", 5)).apply();
                JSONArray cmds = hb.getJSONArray("commands");
                for (int i = 0; i < cmds.length(); i++) {
                    JSONObject c = cmds.getJSONObject(i);
                    String op = c.getString("op");
                    if ("rotate".equals(op)) {
                        rotate(panel, token, c.getInt("id"));
                    } else if ("data_off".equals(op)) {
                        dataOff(panel, token, c.getInt("id"), c.optString("arg", "5"));
                    } else if ("data_on".equals(op)) {
                        dataOn(panel, token, c.getInt("id"));
                    }
                }
                status = frpcAlive() ? "Connected" : "Tunnel program not running";
                updateNotification(status + " - " + publicIp);
            } catch (Exception e) {
                status = "Error: " + e.getMessage();
                MainActivity.note(this, "heartbeat: " + e);
                updateNotification(status);
            }
            for (int i = 0; i < 15 && !stopped; i++) {
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
        ProcessBuilder pb = new ProcessBuilder(bin.getAbsolutePath(), "-c", cfg.getAbsolutePath());
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

    // --------------------------------------------------------------- rotate
    private void rotate(String panel, String token, int id) {
        String old = fetchPublicIp();
        boolean ok = false;
        String result;
        String now = old;
        if (!hasRoot()) {
            sendAck(panel, token, id, false, "IP rotation needs root on this phone", old);
            return;
        }
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "svc data disable; sleep 4; svc data enable"});
            int rc = p.waitFor();
            ok = rc == 0;
            result = ok ? "" : "su exited with code " + rc;
        } catch (Exception e) {
            result = "IP rotation needs root on this phone";
        }
        if (ok) {
            for (int i = 0; i < 20 && !stopped; i++) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    return;
                }
                String ip = fetchPublicIp();
                if (ip != null && !ip.equals(old)) {
                    now = ip;
                    break;
                }
            }
            result = old + " -> " + now;
        }
        sendAck(panel, token, id, ok, result, now);
    }

    private void sendAck(String panel, String token, int id, boolean ok, String result, String ip) {
        for (int i = 0; i < 15 && !stopped; i++) {
            try {
                JSONObject body = new JSONObject();
                body.put("id", id);
                body.put("ok", ok);
                body.put("result", result);
                if (ip != null) {
                    body.put("ip", ip);
                }
                http("POST", panel + "/agent/ack", token, body.toString());
                return;
            } catch (Exception e) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    // ----------------------------------------------------- mobile data on/off
    /** True when this phone grants root. A "no" is re-checked every 5 minutes (root managers can be slow to prompt). */
    static boolean hasRoot() {
        if (rooted) {
            return true;
        }
        long now = System.currentTimeMillis();
        if (rootCheckedAt != 0 && now - rootCheckedAt < 300000L) {
            return false;
        }
        rootCheckedAt = now;
        final int[] rc = {-1};
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                rc[0] = su("id");
            }
        });
        t.start();
        try {
            t.join(6000);
        } catch (InterruptedException ignored) {
        }
        rooted = rc[0] == 0;
        return rooted;
    }

    private static int su(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            return p.waitFor();
        } catch (Exception e) {
            return -1;
        }
    }

    private boolean mobileDataOn() {
        try {
            return Settings.Global.getInt(getContentResolver(), "mobile_data", 1) == 1;
        } catch (Exception e) {
            return true;
        }
    }

    /** Panel asked for data off. Answer first (the link dies right after), then switch it off. */
    private void dataOff(String panel, String token, int id, String arg) {
        int mins = 5;
        try {
            mins = Math.max(1, Math.min(60, Integer.parseInt(arg)));
        } catch (Exception ignored) {
        }
        if (!hasRoot()) {
            sendAck(panel, token, id, false, "mobile data control needs root on this phone", null);
            return;
        }
        SharedPreferences sp = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        sp.edit().putLong("auto_on_at", System.currentTimeMillis() + mins * 60000L).apply();
        alerted = false;
        sendAck(panel, token, id, true, "data off for " + mins + " min", null);
        su("svc data disable");
    }

    private void dataOn(String panel, String token, int id) {
        boolean ok = su("svc data enable") == 0;
        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit().putLong("auto_on_at", 0).apply();
        sendAck(panel, token, id, ok, ok ? "data on" : "mobile data control needs root on this phone", null);
    }

    /**
     * Runs on its own thread because the panel cannot be reached while data is off.
     * Whenever mobile data is off (from the panel or by hand) it is switched back on after a few minutes.
     */
    private void watchLoop() {
        SharedPreferences sp = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        while (!stopped) {
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                return;
            }
            try {
                int mins = sp.getInt("auto_on_min", 5);
                long at = sp.getLong("auto_on_at", 0);
                long now = System.currentTimeMillis();
                if (mobileDataOn()) {
                    if (at != 0) {
                        sp.edit().putLong("auto_on_at", 0).apply();
                    }
                    alerted = false;
                    continue;
                }
                if (mins <= 0) {
                    continue;
                }
                if (at == 0) {  // turned off by hand: start the countdown
                    at = now + mins * 60000L;
                    sp.edit().putLong("auto_on_at", at).apply();
                }
                if (now >= at) {
                    if (su("svc data enable") == 0) {
                        sp.edit().putLong("auto_on_at", 0).apply();
                    } else {
                        sp.edit().putLong("auto_on_at", now + 60000L).apply();  // try again in a minute
                        if (!alerted) {
                            alerted = true;
                            alertDataOff();
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void alertDataOff() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(Settings.ACTION_WIRELESS_SETTINGS),
                PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel("alert", "Needs attention", NotificationManager.IMPORTANCE_HIGH));
            b = new Notification.Builder(this, "alert");
        } else {
            b = new Notification.Builder(this);
        }
        nm.notify(2, b.setContentTitle("Mobile data is off")
                .setContentText("This phone is offline. Tap to turn mobile data back on.")
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build());
    }

    // ----------------------------------------------------------------- info
    private JSONObject info() throws Exception {
        JSONObject o = new JSONObject();
        long nowMs = System.currentTimeMillis();
        if (cachedIp == null || nowMs - cachedIpAt > 45000L) {  // one lookup per 45 s keeps heartbeats quick
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
        o.put("model", Build.MODEL);
        o.put("android", Build.VERSION.RELEASE);
        String carrier = "";
        TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        if (tm != null) {
            carrier = tm.getNetworkOperatorName();
        }
        o.put("carrier", carrier);
        networkType = detectNetworkType();
        o.put("network_type", networkType);
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
                o.put("charging", st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL);
            }
        } catch (Exception e) {
            MainActivity.note(this, "battery intent: " + e);
        }
        if (pct < 0 && Build.VERSION.SDK_INT >= 21) {  // second source if the broadcast gives nothing
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
        o.put("data_enabled", mobileDataOn());
        o.put("can_control", hasRoot());
        return o;
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
        } catch (Exception ignored) {
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

    // ------------------------------------------------------- 2-minute internet test
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
            for (int i = 0; i < 120 && !stopped; i++) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    /** 5 requests at once (4 HTTPS, 1 HTTP, one of them by IP so DNS problems show up separately). */
    private void runSelfTest() throws Exception {
        final String[] urls = {
                "https://www.google.com/generate_204",
                "https://connectivitycheck.gstatic.com/generate_204",
                "https://www.cloudflare.com/cdn-cgi/trace",
                "https://1.1.1.1/cdn-cgi/trace",
                "http://clients3.google.com/generate_204"};
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
        selfTest = ok + "/" + urls.length + (ok > 0 ? " OK, " + med + " ms" : " - no internet");
    }

    // ----------------------------------------------------------------- http
    private static String http(String method, String url, String token, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Authorization", "Bearer " + token);
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
            throw new Exception("panel answered HTTP " + code);
        }
        return readAll(c.getInputStream());
    }

    private static String readAll(InputStream in) throws Exception {
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
        if (Build.VERSION.SDK_INT >= 29) {
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
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Node status", NotificationManager.IMPORTANCE_LOW));
            }
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle("Node Console")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
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
