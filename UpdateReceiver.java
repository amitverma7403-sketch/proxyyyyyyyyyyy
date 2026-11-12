package com.nodemax.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

/** Gets Android's answer to an in-app update: asks the person to tap Install, or keeps a readable reason when it failed. */
public class UpdateReceiver extends BroadcastReceiver {
    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context c, Intent in) {
        int st = in.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent ask = Build.VERSION.SDK_INT >= 33 ? in.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class) : (Intent) in.getParcelableExtra(Intent.EXTRA_INTENT);
            if (ask != null) {
                ask.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    c.startActivity(ask);
                } catch (Exception e) {
                    Updater.setMessage(c, "Open NodeMax and tap Update again.");
                }
            }
            return;
        }
        if (st == PackageInstaller.STATUS_SUCCESS) {
            Updater.setMessage(c, "");
            return;
        }
        String why = in.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        String msg;
        if (st == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE || st == PackageInstaller.STATUS_FAILURE_CONFLICT) {
            msg = "This update was signed with a different key than the app on this phone. Uninstall NodeMax once, install the new download, and later updates will work from inside the app.";
        } else if (st == PackageInstaller.STATUS_FAILURE_ABORTED) {
            msg = "The update was cancelled.";
        } else if (st == PackageInstaller.STATUS_FAILURE_STORAGE) {
            msg = "Not enough free space on the phone for the update.";
        } else {
            msg = "The update could not be installed" + (why != null && why.length() > 0 ? " (" + why + ")." : ".");
        }
        Updater.setMessage(c, msg);
    }
}
