package com.gao.joyosenolimit;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;

public class HookInit implements IXposedHookLoadPackage {

    public static final String LOG_TAG = "LSPosed-JoyoseNoLimit";
    private static final String TARGET_PKG = "com.xiaomi.joyose";
    private static final String SELF_PKG = "com.gao.joyosenolimit";
    private static final String DEFAULT_TARGET_CLASS = "com.xiaomi.joyose.utils.w";
    public static final String ACTION_INTERCEPTED = "com.gao.joyosenolimit.ACTION_INTERCEPTED";

    private static void log(String msg) {
        Log.e(LOG_TAG, "[JoyoseNoLimit] " + msg);
        try {
            XposedBridge.log("[JoyoseNoLimit] " + msg);
        } catch (Throwable ignored) {
        }
    }

    private static void logError(String msg, Throwable t) {
        Log.e(LOG_TAG, "[JoyoseNoLimit] " + msg, t);
        try {
            XposedBridge.log("[JoyoseNoLimit] " + msg);
            if (t != null) XposedBridge.log(t);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        // 1. Self activation check hook
        if (SELF_PKG.equals(lpparam.packageName)) {
            log("Module loaded into self (" + lpparam.packageName + ")");
            hookSelf(lpparam);
            return;
        }

        // 2. Target Joyose package hook (match package name or process name)
        boolean isJoyose = TARGET_PKG.equals(lpparam.packageName) || TARGET_PKG.equals(lpparam.processName);
        if (!isJoyose) {
            return;
        }

        log("Successfully injected into " + lpparam.packageName + " (PID process: " + lpparam.processName + ")");

        // Clean stale /data/system/mcd/df on Joyose startup
        clearMcdDfFile();

        // Hook JoyoseApplication.onCreate for lifecycle confirmation
        try {
            XposedHelpers.findAndHookMethod(
                "com.xiaomi.joyose.JoyoseApplication",
                lpparam.classLoader,
                "onCreate",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        log("JoyoseApplication.onCreate() finished. Frame-limit interception is active!");
                    }
                }
            );
        } catch (Throwable t) {
            log("Note: JoyoseApplication.onCreate not hooked (" + t.getMessage() + "), hooking sinks directly.");
        }

        // Hook Joyose frame-rate limiter sinks dynamically by method signatures
        try {
            Class<?> targetClass = findTargetClass(lpparam.classLoader);
            if (targetClass == null) {
                logError("ERROR: Failed to find MiDFUtils class in Joyose!", null);
                return;
            }

            hookMiDFUtils(targetClass);
            log("Successfully hooked MiDFUtils (" + targetClass.getName() + ")!");

        } catch (Throwable t) {
            logError("Unexpected error while hooking Joyose:", t);
        }
    }

    /**
     * Hook self to verify module active status in UI
     */
    private void hookSelf(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.gao.joyosenolimit.MainActivity",
                lpparam.classLoader,
                "isModuleActive",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        param.setResult(true);
                    }
                }
            );
        } catch (Throwable ignored) {
        }
    }

    /**
     * Find MiDFUtils class dynamically with signature verification
     */
    private Class<?> findTargetClass(ClassLoader classLoader) {
        // 1. Try standard / known candidate class names first
        String[] candidates = {
            DEFAULT_TARGET_CLASS,
            "com.xiaomi.joyose.utils.v",
            "com.xiaomi.joyose.utils.x",
            "com.xiaomi.joyose.utils.y",
            "com.xiaomi.joyose.utils.z",
            "com.xiaomi.joyose.utils.u",
            "com.xiaomi.joyose.utils.a0",
            "com.xiaomi.joyose.utils.b0",
            "com.xiaomi.joyose.utils.c0",
            "com.xiaomi.joyose.utils.MiDFUtils"
        };

        for (String c : candidates) {
            try {
                Class<?> cls = classLoader.loadClass(c);
                if (hasDfSignatures(cls)) {
                    log("Found target class with DF signatures: " + c);
                    return cls;
                }
            } catch (Throwable ignored) {
            }
        }

        // 2. Fallback: scan all single letter classes in com.xiaomi.joyose.utils
        for (char ch = 'a'; ch <= 'z'; ch++) {
            String c = "com.xiaomi.joyose.utils." + ch;
            try {
                Class<?> cls = classLoader.loadClass(c);
                if (hasDfSignatures(cls)) {
                    log("Found target class via alphabet scan: " + c);
                    return cls;
                }
            } catch (Throwable ignored) {
            }
        }

        return null;
    }

    private boolean hasDfSignatures(Class<?> cls) {
        boolean hasFloat = false;
        boolean hasInt = false;
        for (Method m : cls.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 4 && p[0] == Context.class && p[1] == String.class) {
                if (p[2] == float.class && p[3] == int.class) hasFloat = true;
                if (p[2] == int.class && p[3] == int.class) hasInt = true;
            }
        }
        return hasFloat && hasInt;
    }

    /**
     * Dynamically hook float and int frame limit methods by parameter signature,
     * not hardcoded method names (which differ across Joyose versions: f/g vs g/h, etc.).
     */
    private void hookMiDFUtils(Class<?> targetClass) {
        int hookedCount = 0;

        for (final Method method : targetClass.getDeclaredMethods()) {
            Class<?>[] p = method.getParameterTypes();

            // 1. Float limiter sink: (Context, String pkg, float fps, int reason)
            if (p.length == 4 && p[0] == Context.class && p[1] == String.class && p[2] == float.class && p[3] == int.class) {
                try {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            String pkg = (String) param.args[1];
                            float fps = (Float) param.args[2];
                            int reason = (Integer) param.args[3];

                            if (pkg != null && fps > 0.0f) {
                                param.setResult(null);
                                clearMcdDfFile();
                                log(">>> Intercepted & Blocked " + method.getName() + " (float) -> pkg=" + pkg + ", targetFps=" + fps + ", reason=" + reason);
                                recordInterception((Context) param.args[0], pkg, fps, reason);
                            } else {
                                log("Pass-through " + method.getName() + " (float restore/clear): pkg=" + pkg + ", fps=" + fps);
                            }
                        }
                    });
                    hookedCount++;
                    log("Hooked float limiter method: " + method.getName() + "(Context, String, float, int)");
                } catch (Throwable t) {
                    logError("Failed to hook float limiter " + method.getName(), t);
                }
            }
            // 2. Integer limiter sink: (Context, String pkg, int fps, int reason)
            else if (p.length == 4 && p[0] == Context.class && p[1] == String.class && p[2] == int.class && p[3] == int.class) {
                try {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            String pkg = (String) param.args[1];
                            int fps = (Integer) param.args[2];
                            int reason = (Integer) param.args[3];

                            if (pkg != null && fps > 0) {
                                param.setResult(null);
                                clearMcdDfFile();
                                log(">>> Intercepted & Blocked " + method.getName() + " (int) -> pkg=" + pkg + ", targetFps=" + fps + ", reason=" + reason);
                                recordInterception((Context) param.args[0], pkg, (float) fps, reason);
                            } else {
                                log("Pass-through " + method.getName() + " (int restore/clear): pkg=" + pkg + ", fps=" + fps);
                            }
                        }
                    });
                    hookedCount++;
                    log("Hooked int limiter method: " + method.getName() + "(Context, String, int, int)");
                } catch (Throwable t) {
                    logError("Failed to hook int limiter " + method.getName(), t);
                }
            }
            // 3. State getter: ()I (e.g. w.d()) -> always return -1
            else if (p.length == 0 && method.getReturnType() == int.class) {
                try {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            param.setResult(-1);
                        }
                    });
                    hookedCount++;
                    log("Hooked frame status getter: " + method.getName() + "() -> always return -1");
                } catch (Throwable t) {
                    logError("Failed to hook state getter " + method.getName(), t);
                }
            }
        }

        log("Total methods hooked in " + targetClass.getName() + ": " + hookedCount);
    }

    /**
     * Broadcast interception event directly to app (no files in /data/system/mcd)
     */
    private static void recordInterception(Context context, String pkg, float fps, int reason) {
        if (context != null) {
            try {
                Intent intent = new Intent(ACTION_INTERCEPTED);
                intent.setPackage(SELF_PKG);
                intent.putExtra("pkg", pkg);
                intent.putExtra("fps", fps);
                intent.putExtra("reason", reason);
                intent.putExtra("timestamp", System.currentTimeMillis());
                context.sendBroadcast(intent);
            } catch (Throwable t) {
                logError("Failed to send interception broadcast: " + t.getMessage(), t);
            }
        }
    }

    /**
     * Clear /data/system/mcd/df & /data/system/mcd/dfi if leftover limit exists
     */
    private static void clearMcdDfFile() {
        try {
            File df = new File("/data/system/mcd/df");
            if (df.exists() && df.length() > 0) {
                new FileOutputStream(df).close();
                log("Successfully cleared stale /data/system/mcd/df (truncated to 0 bytes)");
            }
        } catch (Throwable t) {
            // normal
        }
        try {
            File dfi = new File("/data/system/mcd/dfi");
            if (dfi.exists() && dfi.length() > 0) {
                new FileOutputStream(dfi).close();
                log("Successfully cleared stale /data/system/mcd/dfi (truncated to 0 bytes)");
            }
        } catch (Throwable t) {
            // normal
        }
    }
}
