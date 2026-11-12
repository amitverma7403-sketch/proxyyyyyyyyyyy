package com.nodemax.app;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Updates the app from inside the app, for the build that is installed straight from the download link.
 * The server says which build is newest (it reads the GitHub release). The app downloads that file, checks its size and
 * SHA-256, and hands it to Android's installer. Android itself refuses a file that was not signed with the same key,
 * so a tampered download cannot install. The person taps Install once; nothing happens silently.
 * Not used when the app came from Google Play: Play updates it.
 */
final class Updater {
    static final String ACTION = "com.nodemax.app.UPDATE_STATUS";

    static final class Info {
        long latest;
        String name = "";
        String url = "";
        String sha256 = "";
        String note = "";
        long size;
        boolean required;
    }

    interface Progress {
        void percent(int p);
    }

    /** This install's build number (the release tag build-N). */
    static long myCode(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /** False when Google Play installed the app, because Play rules do not allow an app to update itself. */
    static boolean allowed(Context c) {
        try {
            String src;
            if (Build.VERSION.SDK_INT >= 30) {
                src = c.getPackageManager().getInstallSourceInfo(c.getPackageName()).getInstallingPackageName();
            } else {
                src = c.getPackageManager().getInstallerPackageName(c.getPackageName());
            }
            return !"com.android.vending".equals(src);
        } catch (Exception e) {
            return true;
        }
    }

    /** Asks the server for the newest build. Blocking. Returns null when it cannot tell. */
    static Info check(Context c) {
        try {
            HttpURLConnection h = (HttpURLConnection) new URL(App.SERVER + "/app/api/version?code=" + myCode(c)).openConnection();
            h.setConnectTimeout(10000);
            h.setReadTimeout(15000);
            if (h.getResponseCode() != 200) {
                return null;
            }
            JSONObject j = new JSONObject(FrpcService.readAll(h.getInputStream()));
            Info i = new Info();
            i.latest = j.optLong("latest", 0);
            i.name = j.optString("name", "");
            i.url = j.optString("url", "");
            i.sha256 = j.optString("sha256", "");
            i.note = j.optString("note", "");
            i.size = j.optLong("size", 0);
            i.required = j.optBoolean("required", false);
            return i;
        } catch (Exception e) {
            return null;
        }
    }

    /** Downloads the APK into the app's private cache and checks it. Blocking. Throws a readable message on failure. */
    static File download(Context c, Info info, Progress cb) throws Exception {
        if (!info.url.startsWith("https://")) {
            throw new Exception("The update link is not secure, so it was not used.");
        }
        File dir = new File(c.getCacheDir(), "update");
        dir.mkdirs();
        File out = new File(dir, "NodeMax.apk");
        HttpURLConnection h = (HttpURLConnection) new URL(info.url).openConnection();
        h.setConnectTimeout(15000);
        h.setReadTimeout(30000);
        h.setInstanceFollowRedirects(true);
        if (h.getResponseCode() != 200) {
            throw new Exception("The download failed (" + h.getResponseCode() + "). Try again in a minute.");
        }
        long total = h.getContentLengthLong();
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        InputStream in = h.getInputStream();
        OutputStream os = new FileOutputStream(out);
        long got = 0;
        int last = -1;
        try {
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                md.update(buf, 0, n);
                got += n;
                if (total > 0 && cb != null) {
                    int p = (int) (got * 100 / total);
                    if (p != last) {
                        last = p;
                        cb.percent(p);
                    }
                }
            }
        } finally {
            os.close();
            in.close();
        }
        long want = info.size > 0 ? info.size : total;
        if (want > 0 && got != want) {
            out.delete();
            throw new Exception("The download was cut short. Try again.");
        }
        if (info.sha256.length() == 64) {
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b));
            }
            if (!sb.toString().equalsIgnoreCase(info.sha256)) {
                out.delete();
                throw new Exception("The downloaded file did not match, so it was not installed.");
            }
        }
        return out;
    }

    /** True when this app may install apps. If not, opens the one settings page where the person switches that on. */
    static boolean ensurePermission(Context c) {
        if (Build.VERSION.SDK_INT < 26 || c.getPackageManager().canRequestPackageInstalls()) {
            return true;
        }
        Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
        return false;
    }

    /** Hands the file to Android's installer. The result arrives in UpdateReceiver. */
    static void install(Context c, File apk) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        if (Build.VERSION.SDK_INT >= 31) {
            sp.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);   // later updates can be silent on Android 12+
        }
        int id = pi.createSession(sp);
        PackageInstaller.Session s = pi.openSession(id);
        try {
            OutputStream os = s.openWrite("NodeMax.apk", 0, apk.length());
            InputStream in = new FileInputStream(apk);
            try {
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
                s.fsync(os);
            } finally {
                in.close();
                os.close();
            }
            Intent i = new Intent(c, UpdateReceiver.class).setAction(ACTION);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            s.commit(PendingIntent.getBroadcast(c, id, i, flags).getIntentSender());
        } catch (Exception e) {
            s.abandon();
            throw e;
        } finally {
            s.close();
        }
    }

    static void setMessage(Context c, String msg) {
        SharedPreferences p = c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        p.edit().putString("update_msg", msg).apply();
    }
}
