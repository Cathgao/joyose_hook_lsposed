package com.gao.joyosenolimit;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

public class MainActivity extends Activity {

    public static final String LOG_TAG = "LSPosed-JoyoseNoLimit";

    private TextView tvStatus;
    private TextView tvMcdDf;
    private Button btnRestartJoyose;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Log.e(LOG_TAG, "[JoyoseNoLimit] MainActivity onCreate() invoked");

        tvStatus = findViewById(R.id.tv_status);
        tvMcdDf = findViewById(R.id.tv_mcddf);
        btnRestartJoyose = findViewById(R.id.btn_restart_joyose);

        updateStatus();

        btnRestartJoyose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                restartJoyose();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
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
            tvStatus.setText("● 模块状态：已加载运行 (LSPosed 模块已全局生效)");
            tvStatus.setTextColor(0xFF2196F3); // Blue
        }

        readMcdDf();
    }

    private void readMcdDf() {
        try {
            File file = new File("/data/system/mcd/df");
            if (!file.exists()) {
                tvMcdDf.setText("/data/system/mcd/df: 文件不存在 (未施加任何限帧)");
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
                tvMcdDf.setText("/data/system/mcd/df: 文件存在 (0 字节空文件，当前无任何限帧)");
            }
        } catch (Exception e) {
            tvMcdDf.setText("/data/system/mcd/df: 读取失败 (" + e.getMessage() + ")");
        }
    }

    private void restartJoyose() {
        Log.i(LOG_TAG, "[JoyoseNoLimit] Attempting to restart Joyose via root...");
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", "killall com.xiaomi.joyose; pkill -9 -f com.xiaomi.joyose"});
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                Toast.makeText(this, "Joyose 进程已重启，Hook 生效！", Toast.LENGTH_SHORT).show();
                Log.i(LOG_TAG, "[JoyoseNoLimit] Joyose restarted successfully");
            } else {
                Toast.makeText(this, "重启 Joyose 失败 (exit " + exitCode + ")", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "执行 root 重启失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        updateStatus();
    }
}
