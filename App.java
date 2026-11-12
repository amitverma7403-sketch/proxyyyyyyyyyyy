package com.nodemax.app;

import android.app.Application;

/** Runs first whenever any part of the app starts: sets up the crash log and push notifications. */
public class App extends Application {
    /** The NodeMax server this app talks to. */
    static final String SERVER = "https://nodemax.dealdecoderz.in";

    @Override
    public void onCreate() {
        super.onCreate();
        MainActivity.installCrashLog(this);
        Push.start(this);
    }
}
