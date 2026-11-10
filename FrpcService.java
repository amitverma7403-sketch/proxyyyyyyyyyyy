package com.nodeconsole.node;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
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
 * Keeps this phone connected to your panel:
 * - heartbeat every 15s (public IP, carrier, network type)
 * - downloads this device's frpc config and keeps the bundled frpc running
 * - handles "rotate" commands (needs root for now)
 */
public class FrpcService extends Service {
    static volatile String status = "Stopped";
    static volatile String publicIp = "-";
    static volatile String networkType = "-";
    static volatile String lastLog = "";

    private static final String CHANNEL = "node";
    private volatile boolean stopped = false;
    private Thread worker;
    private Process frpc;
    private String cfgHash = "";
    private PowerManager.WakeLock wake;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
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
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopped = true;
        if (worker != null) {
            worker.interrupt();
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
                JSONArray cmds = hb.getJSONArray("commands");
                for (int i = 0; i < cmds.length(); i++) {
                    JSONObject c = cmds.getJSONObject(i);
                    if ("rotate".equals(c.getString("op"))) {
                        rotate(panel, token, c.getInt("id"));
                    }
                }
                status = frpcAlive() ? "Connected" : "Tunnel program not running";
                updateNotification(status + " - " + publicIp);
            } catch (Exception e) {
                status = "Error: " + e.getMessage();
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
        for (int i = 0; i < 15 && !stopped; i++) {
            try {
                JSONObject body = new JSONObject();
                body.put("id", id);
                body.put("ok", ok);
                body.put("result", result);
                body.put("ip", now);
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

    // ----------------------------------------------------------------- info
    private JSONObject info() throws Exception {
        JSONObject o = new JSONObject();
        String ip = fetchPublicIp();
        if (ip != null) {
            publicIp = ip;
        }
        o.put("ip", ip);
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
