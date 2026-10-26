package com.gao.joyosenolimit;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

public class StatReceiver extends BroadcastReceiver {

    public static final String PREF_NAME = "joyose_nolimit_stats";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && HookInit.ACTION_INTERCEPTED.equals(intent.getAction())) {
            String pkg = intent.getStringExtra("pkg");
            float fps = intent.getFloatExtra("fps", 0f);
            int reason = intent.getIntExtra("reason", 0);
            long timestamp = intent.getLongExtra("timestamp", System.currentTimeMillis());

            SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            int count = sp.getInt("count", 0) + 1;

            sp.edit()
                .putInt("count", count)
                .putString("pkg", pkg)
                .putFloat("fps", fps)
                .putInt("reason", reason)
                .putLong("timestamp", timestamp)
                .apply();

            Log.e(HookInit.LOG_TAG, "[JoyoseNoLimit] StatReceiver updated count=" + count + " for " + pkg);
        }
    }
}
