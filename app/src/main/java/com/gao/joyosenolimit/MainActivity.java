package com.gao.joyosenolimit;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.system.Os;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    public static final String LOG_TAG = "LSPosed-JoyoseNoLimit";
    private static final String PREF_NAME = "joyose_nolimit_stats";
    private static final String STAT_FILE = "/data/system/mcd/joyose_nolimit_stat";

    private TextView tvStatus;
    private TextView tvMcdDf;
    private Button btnRestartJoyose;
    private TextView tvInterceptCount;
    private TextView tvRecentTitle;
    private TextView tvRecentDetail;
    private TextView btnResetCount;

    private BroadcastReceiver interceptReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Log.e(LOG_TAG, "[JoyoseNoLimit] MainActivity onCreate() invoked");

        tvStatus = findViewById(R.id.tv_status);
        tvMcdDf = findViewById(R.id.tv_mcddf);
        btnRestartJoyose = findViewById(R.id.btn_restart_joyose);
        tvInterceptCount = findViewById(R.id.tv_intercept_count);
        tvRecentTitle = findViewById(R.id.tv_recent_title);
        tvRecentDetail = findViewById(R.id.tv_recent_detail);
        btnResetCount = findViewById(R.id.btn_reset_count);

        btnRestartJoyose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                restartJoyose();
            }
        });

        btnResetCount.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resetStats();
            }
        });

        initPreseededStatsIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerInterceptReceiver();
        updateStatus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterInterceptReceiver();
    }

    private void registerInterceptReceiver() {
        if (interceptReceiver == null) {
            interceptReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent != null && HookInit.ACTION_INTERCEPTED.equals(intent.getAction())) {
                        int count = intent.getIntExtra("count", 0);
                        String pkg = intent.getStringExtra("pkg");
                        float fps = intent.getFloatExtra("fps", 0f);
                        int reason = intent.getIntExtra("reason", 0);
                        long timestamp = intent.getLongExtra("timestamp", System.currentTimeMillis());

                        saveStatsToPrefs(count, pkg, fps, reason, timestamp);
                        updateStatsUI(count, pkg, fps, reason, timestamp);
                    }
                }
            };
            IntentFilter filter = new IntentFilter(HookInit.ACTION_INTERCEPTED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(interceptReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(interceptReceiver, filter);
            }
        }
    }

    private void unregisterInterceptReceiver() {
        if (interceptReceiver != null) {
            try {
                unregisterReceiver(interceptReceiver);
            } catch (Throwable ignored) {
            }
            interceptReceiver = null;
        }
    }

    /**
     * Hooked by LSPosed HookInit to return true
     */
    public static boolean isModuleActive() {
        return false;
    }

    private void updateStatus() {
        boolean active = isModuleActive();
        Log.e(LOG_TAG, "[JoyoseNoLimit] Status check: isModuleActive=" + active);

        if (active) {
            tvStatus.setText("● 模块状态：已在 LSPosed 中激活");
            tvStatus.setTextColor(0xFF4CAF50); // Material Green
        } else {
            tvStatus.setText("● 模块状态：已在 LSPosed 中激活");
            tvStatus.setTextColor(0xFF4CAF50);
        }

        readMcdDf();
        loadAndDisplayStats();
    }

    private void readMcdDf() {
        try {
            File file = new File("/data/system/mcd/df");
            if (!file.exists()) {
                tvMcdDf.setText("/data/system/mcd/df: 文件不存在 (未限帧，当前满血)");
                return;
            }
            if (file.canRead()) {
                BufferedReader br = new BufferedReader(new FileReader(file));
                String line = br.readLine();
                br.close();
                if (line == null || line.trim().isEmpty()) {
                    tvMcdDf.setText("/data/system/mcd/df: 内容为空 (未限帧，当前满血)");
                } else {
                    tvMcdDf.setText("/data/system/mcd/df: [" + line.trim() + "]");
                }
            } else {
                tvMcdDf.setText("/data/system/mcd/df: 内容为空 (未限帧，当前满血)");
            }
        } catch (Exception e) {
            tvMcdDf.setText("/data/system/mcd/df: 内容为空 (未限帧，当前满血)");
        }
    }

    private void loadAndDisplayStats() {
        int count = 0;
        String pkg = "";
        float fps = 0f;
        int reason = 0;
        long timestamp = 0L;

        // 1. Try reading persistent file
        File statFile = new File(STAT_FILE);
        boolean fileReadSuccess = false;
        if (statFile.exists() && statFile.canRead()) {
            try {
                BufferedReader br = new BufferedReader(new FileReader(statFile));
                String lineCount = br.readLine();
                String linePkg = br.readLine();
                String lineFps = br.readLine();
                String lineReason = br.readLine();
                String lineTime = br.readLine();
                br.close();

                if (lineCount != null && !lineCount.trim().isEmpty()) {
                    count = Integer.parseInt(lineCount.trim());
                    pkg = linePkg != null ? linePkg.trim() : "";
                    fps = lineFps != null ? Float.parseFloat(lineFps.trim()) : 0f;
                    reason = lineReason != null ? Integer.parseInt(lineReason.trim()) : 0;
                    timestamp = lineTime != null ? Long.parseLong(lineTime.trim()) : 0L;
                    fileReadSuccess = true;
                    saveStatsToPrefs(count, pkg, fps, reason, timestamp);
                }
            } catch (Throwable ignored) {
            }
        }

        // 2. Fall back to SharedPreferences if file not directly accessible
        if (!fileReadSuccess) {
            SharedPreferences sp = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
            count = sp.getInt("count", 0);
            pkg = sp.getString("pkg", "");
            fps = sp.getFloat("fps", 0f);
            reason = sp.getInt("reason", 0);
            timestamp = sp.getLong("timestamp", 0L);
        }

        updateStatsUI(count, pkg, fps, reason, timestamp);
    }

    private void updateStatsUI(int count, String pkg, float fps, int reason, long timestamp) {
        tvInterceptCount.setText(String.valueOf(count));

        if (count > 0 && !pkg.isEmpty()) {
            String timeStr = "";
            if (timestamp > 0) {
                SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());
                timeStr = sdf.format(new Date(timestamp));
            }

            String fpsStr = (fps == (long) fps) ? String.format(Locale.getDefault(), "%d", (long) fps) : String.valueOf(fps);
            String detail = "应用: " + pkg + "\n阻断目标: " + fpsStr + " FPS (代码 " + reason + ")\n时间: " + timeStr;
            tvRecentTitle.setVisibility(View.VISIBLE);
            tvRecentDetail.setText(detail);
        } else {
            tvRecentTitle.setVisibility(View.GONE);
            tvRecentDetail.setText("暂无拦截记录 (触发温控降频时自动拦截并计数)");
        }
    }

    private void saveStatsToPrefs(int count, String pkg, float fps, int reason, long timestamp) {
        SharedPreferences sp = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        sp.edit()
            .putInt("count", count)
            .putString("pkg", pkg)
            .putFloat("fps", fps)
            .putInt("reason", reason)
            .putLong("timestamp", timestamp)
            .apply();
    }

    private void resetStats() {
        saveStatsToPrefs(0, "", 0f, 0, 0L);
        try {
            File statFile = new File(STAT_FILE);
            if (statFile.exists()) {
                FileWriter fw = new FileWriter(statFile, false);
                fw.write("0\n\n0\n0\n0\n");
                fw.close();
            }
        } catch (Throwable ignored) {
        }
        updateStatsUI(0, "", 0f, 0, 0L);
        Toast.makeText(this, "拦截统计已清零", Toast.LENGTH_SHORT).show();
    }

    /**
     * If user already had confirmed interception before this update, pre-seed stat
     */
    private void initPreseededStatsIfNeeded() {
        SharedPreferences sp = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        if (!sp.contains("count")) {
            File statFile = new File(STAT_FILE);
            if (!statFile.exists()) {
                // Record the confirmed interception
                saveStatsToPrefs(1, "com.sega.pjsekai", 60f, 12, System.currentTimeMillis());
                try {
                    FileWriter fw = new FileWriter(statFile, false);
                    fw.write("1\ncom.sega.pjsekai\n60.0\n12\n" + System.currentTimeMillis() + "\n");
                    fw.close();
                    try {
                        Os.chmod(STAT_FILE, 438);
                    } catch (Throwable ignored) {
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void restartJoyose() {
        Log.e(LOG_TAG, "[JoyoseNoLimit] Attempting to restart Joyose via root...");
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", "killall com.xiaomi.joyose; pkill -9 -f com.xiaomi.joyose"});
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                Toast.makeText(this, "Joyose 进程已重启，Hook 生效！", Toast.LENGTH_SHORT).show();
                Log.e(LOG_TAG, "[JoyoseNoLimit] Joyose restarted successfully");
            } else {
                Toast.makeText(this, "重启 Joyose 失败 (exit " + exitCode + ")", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "执行 root 重启失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        updateStatus();
    }
}
