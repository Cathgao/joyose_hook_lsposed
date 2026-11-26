package com.gao.joyosenolimit;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    public static final String LOG_TAG = "LSPosed-JoyoseNoLimit";
    private static final String JOYOSE_STAT_PATH =
            "/data/user/0/com.xiaomi.joyose/files/" + HookInit.STAT_FILE_NAME;
    private static final String JOYOSE_STAT_MIRROR_PATH =
            "/data_mirror/data_ce/null/0/com.xiaomi.joyose/files/" + HookInit.STAT_FILE_NAME;
    private static final String JOYOSE_STAT_TMP_PATH = JOYOSE_STAT_PATH + ".tmp";
    private static final String JOYOSE_STAT_MIRROR_TMP_PATH = JOYOSE_STAT_MIRROR_PATH + ".tmp";
    private static final String LEGACY_MCD_STAT_PATH = "/data/system/mcd/joyose_nolimit_stat";
    private static final String MCD_DF_PATH = "/data/system/mcd/df";
    private static final String MARKER_DF = "__DF_BEGIN__";
    private static final String MARKER_STAT = "__STAT_BEGIN__";

    private TextView tvStatus;
    private TextView tvMcdDf;
    private Button btnRestartJoyose;
    private TextView tvInterceptCount;
    private TextView tvRecentTitle;
    private TextView tvRecentDetail;
    private TextView btnResetCount;

    private BroadcastReceiver dynamicReceiver;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

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
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerDynamicReceiver();
        updateStatus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterDynamicReceiver();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ioExecutor.shutdownNow();
    }

    private void registerDynamicReceiver() {
        if (dynamicReceiver == null) {
            dynamicReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent != null && HookInit.ACTION_INTERCEPTED.equals(intent.getAction())) {
                        refreshJoyoseDataAsync();
                    }
                }
            };
            IntentFilter filter = new IntentFilter(HookInit.ACTION_INTERCEPTED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(dynamicReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(dynamicReceiver, filter);
            }
        }
    }

    private void unregisterDynamicReceiver() {
        if (dynamicReceiver != null) {
            try {
                unregisterReceiver(dynamicReceiver);
            } catch (Throwable ignored) {
            }
            dynamicReceiver = null;
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
            tvStatus.setTextColor(0xFF4CAF50);
        } else {
            tvStatus.setText("● 模块状态：未激活 (请在 LSPosed 中勾选并重启)");
            tvStatus.setTextColor(0xFFF44336);
        }

        refreshJoyoseDataAsync();
    }

    /**
     * Reads /data/system/mcd/df and Joyose's persistent stat file on demand via root.
     */
    private void refreshJoyoseDataAsync() {
        ioExecutor.execute(new Runnable() {
            @Override
            public void run() {
                final String cmd = "echo " + MARKER_DF + "; "
                        + "if [ ! -e " + MCD_DF_PATH + " ]; then echo __MISSING__; "
                        + "else cat " + MCD_DF_PATH + "; echo ''; fi; "
                        + "echo " + MARKER_STAT + "; "
                        + "if [ -f " + JOYOSE_STAT_PATH + " ]; then cat " + JOYOSE_STAT_PATH + "; "
                        + "elif [ -f " + JOYOSE_STAT_MIRROR_PATH + " ]; then cat " + JOYOSE_STAT_MIRROR_PATH + "; fi";

                final RootCommandResult res = runRootCommand(cmd);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        if (!res.success) {
                            tvMcdDf.setText("/data/system/mcd/df: 需要 Root 权限读取");
                            tvInterceptCount.setText("-");
                            tvRecentTitle.setVisibility(View.GONE);
                            tvRecentDetail.setText("无法读取统计文件，请授予本应用 Root 权限");
                            return;
                        }
                        applySnapshot(res.lines);
                    }
                });
            }
        });
    }

    private void applySnapshot(List<String> lines) {
        List<String> dfLines = new ArrayList<>();
        List<String> statLines = new ArrayList<>();
        int section = 0;
        for (String line : lines) {
            if (MARKER_DF.equals(line)) {
                section = 1;
            } else if (MARKER_STAT.equals(line)) {
                section = 2;
            } else if (section == 1) {
                dfLines.add(line);
            } else if (section == 2) {
                statLines.add(line);
            }
        }

        // Update /data/system/mcd/df status
        String dfJoined = joinNonEmpty(dfLines);
        if ("__MISSING__".equals(dfJoined)) {
            tvMcdDf.setText("/data/system/mcd/df: 文件不存在 (未限帧，当前满血)");
        } else if (dfJoined.isEmpty()) {
            tvMcdDf.setText("/data/system/mcd/df: 内容为空 (未限帧，当前满血)");
        } else {
            tvMcdDf.setText("/data/system/mcd/df: [" + dfJoined + "]");
        }

        // Parse Joyose private stat file: count / pkg / fps / reason / timestamp
        int count = 0;
        String pkg = "";
        float fps = 0f;
        int reason = 0;
        long timestamp = 0L;

        if (!statLines.isEmpty()) {
            try {
                count = Integer.parseInt(statLines.get(0).trim());
            } catch (Throwable ignored) {
            }
            if (statLines.size() > 1) pkg = statLines.get(1).trim();
            if (statLines.size() > 2) {
                try {
                    fps = Float.parseFloat(statLines.get(2).trim());
                } catch (Throwable ignored) {
                }
            }
            if (statLines.size() > 3) {
                try {
                    reason = Integer.parseInt(statLines.get(3).trim());
                } catch (Throwable ignored) {
                }
            }
            if (statLines.size() > 4) {
                try {
                    timestamp = Long.parseLong(statLines.get(4).trim());
                } catch (Throwable ignored) {
                }
            }
        }

        updateStatsUI(Math.max(0, count), pkg, fps, reason, timestamp);
    }

    private static String joinNonEmpty(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String t = line.trim();
            if (!t.isEmpty()) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(t);
            }
        }
        return sb.toString();
    }

    private void updateStatsUI(int count, String pkg, float fps, int reason, long timestamp) {
        tvInterceptCount.setText(String.valueOf(count));

        if (count > 0 && !pkg.isEmpty()) {
            String timeStr = "";
            if (timestamp > 0) {
                SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());
                timeStr = sdf.format(new Date(timestamp));
            }

            String fpsStr = (fps == (long) fps)
                    ? String.format(Locale.getDefault(), "%d", (long) fps)
                    : String.valueOf(fps);
            String detail = "应用: " + pkg
                    + "\n阻断目标: " + fpsStr + " FPS (代码 " + reason + ")"
                    + "\n时间: " + timeStr;
            tvRecentTitle.setVisibility(View.VISIBLE);
            tvRecentDetail.setText(detail);
        } else {
            tvRecentTitle.setVisibility(View.GONE);
            tvRecentDetail.setText("暂无拦截记录");
        }
    }

    private void resetStats() {
        ioExecutor.execute(new Runnable() {
            @Override
            public void run() {
                String cmd = "rm -f " + JOYOSE_STAT_PATH + " " + JOYOSE_STAT_TMP_PATH + " "
                        + JOYOSE_STAT_MIRROR_PATH + " " + JOYOSE_STAT_MIRROR_TMP_PATH + " "
                        + LEGACY_MCD_STAT_PATH;
                final RootCommandResult res = runRootCommand(cmd);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        if (res.success) {
                            updateStatsUI(0, "", 0f, 0, 0L);
                            Toast.makeText(MainActivity.this, "拦截统计已清零", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(MainActivity.this, "清零失败 (exit " + res.exitCode + ")", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        });
    }

    private void restartJoyose() {
        Log.e(LOG_TAG, "[JoyoseNoLimit] Attempting to restart Joyose via root...");
        ioExecutor.execute(new Runnable() {
            @Override
            public void run() {
                String cmd = "P=$(pidof com.xiaomi.joyose); [ -n \"$P\" ] && kill -9 $P; exit 0";
                final RootCommandResult res = runRootCommand(cmd);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        if (res.success) {
                            Toast.makeText(MainActivity.this, "Joyose 进程已重启，Hook 生效！", Toast.LENGTH_SHORT).show();
                            Log.e(LOG_TAG, "[JoyoseNoLimit] Joyose restarted successfully");
                        } else {
                            Toast.makeText(MainActivity.this, "重启 Joyose 失败 (exit " + res.exitCode + ")", Toast.LENGTH_SHORT).show();
                        }
                        updateStatus();
                    }
                });
            }
        });
    }

    private static RootCommandResult runRootCommand(String cmd) {
        RootCommandResult result = execSu(new String[]{"su", "-M", "-c", cmd});
        if (!result.success) {
            result = execSu(new String[]{"su", "-c", cmd});
        }
        return result;
    }

    private static RootCommandResult execSu(String[] argv) {
        List<String> lines = new ArrayList<>();
        try {
            Process process = Runtime.getRuntime().exec(argv);
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    lines.add(line);
                }
            }
            int exitCode = process.waitFor();
            return new RootCommandResult(exitCode == 0, exitCode, lines);
        } catch (Exception e) {
            Log.e(LOG_TAG, "[JoyoseNoLimit] Root command failed: " + e.getMessage());
            return new RootCommandResult(false, -1, lines);
        }
    }

    private static class RootCommandResult {
        final boolean success;
        final int exitCode;
        final List<String> lines;

        RootCommandResult(boolean success, int exitCode, List<String> lines) {
            this.success = success;
            this.exitCode = exitCode;
            this.lines = lines;
        }
    }
}
