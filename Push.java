package com.nodemax.app;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONObject;

/**
 * Push notifications through Firebase. The Firebase project details are not built into the app:
 * the server sends them after sign-in (see /app/api/me), so push can be switched on later without a new APK.
 * Until then this class does nothing, and notifications arrive over the app's own connection.
 */
final class Push {
    private Push() {
    }

    /** Remember the Firebase details the server sent, then start push if it is not running yet. */
    static void configure(Context ctx, JSONObject fb) {
        if (fb == null) {
            return;
        }
        SharedPreferences sp = ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        sp.edit().putString("fb_app", fb.optString("app_id")).putString("fb_key", fb.optString("api_key"))
                .putString("fb_project", fb.optString("project_id")).putString("fb_sender", fb.optString("sender_id")).apply();
        start(ctx);
    }

    static void start(Context ctx) {
        try {
            final Context app = ctx.getApplicationContext();
            final SharedPreferences sp = app.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
            String appId = sp.getString("fb_app", ""), key = sp.getString("fb_key", "");
            String project = sp.getString("fb_project", ""), sender = sp.getString("fb_sender", "");
            if (appId.length() == 0 || key.length() == 0 || project.length() == 0 || sender.length() == 0) {
                return;
            }
            if (FirebaseApp.getApps(app).isEmpty()) {
                FirebaseApp.initializeApp(app, new FirebaseOptions.Builder().setApplicationId(appId).setApiKey(key)
                        .setProjectId(project).setGcmSenderId(sender).build());
            }
            FirebaseMessaging.getInstance().getToken().addOnCompleteListener(new OnCompleteListener<String>() {
                @Override
                public void onComplete(Task<String> task) {
                    try {
                        if (task.isSuccessful() && task.getResult() != null) {
                            sp.edit().putString("fcm_token", task.getResult()).apply();  // goes to the server with the next heartbeat
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable e) {
            MainActivity.note(ctx, "push setup: " + e);
        }
    }
}
