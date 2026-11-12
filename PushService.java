package com.nodemax.app;

import android.content.SharedPreferences;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;

/** Receives push messages from NodeMax, shows them, and tells the server each one arrived. */
public class PushService extends FirebaseMessagingService {
    @Override
    public void onNewToken(String token) {
        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit().putString("fcm_token", token).apply();
    }

    @Override
    public void onMessageReceived(RemoteMessage msg) {
        try {
            Map<String, String> d = msg.getData();
            String title = d.get("title"), body = d.get("body");
            if (title == null && msg.getNotification() != null) {
                title = msg.getNotification().getTitle();
                body = msg.getNotification().getBody();
            }
            if (title == null) {
                return;
            }
            int id = 0;
            try {
                id = Integer.parseInt(String.valueOf(d.get("notice_id")));
            } catch (Exception ignored) {
            }
            FrpcService.showNotice(this, 1000 + id, title, body == null ? "" : body);
            SharedPreferences sp = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
            String panel = sp.getString("panel", ""), token = sp.getString("token", "");
            if (id > 0 && panel.length() > 0 && token.length() > 0) {
                FrpcService.http("POST", panel + "/agent/notice_ack", token,
                        new JSONObject().put("ids", new JSONArray().put(id)).toString());
            }
        } catch (Throwable e) {
            MainActivity.note(this, "push message: " + e);
        }
    }
}
