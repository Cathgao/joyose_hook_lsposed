package com.gao.joyosenolimit;

import android.content.Context;
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
    private static final String TARGET_CLASS = "com.xiaomi.joyose.utils.w";

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

        // Hook Joyose frame-rate limiter sinks
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
     * Find com.xiaomi.joyose.utils.w or fallback candidate
     */
    private Class<?> findTargetClass(ClassLoader classLoader) {
        try {
            return classLoader.loadClass(TARGET_CLASS);
        } catch (ClassNotFoundException e) {
            log("Standard class " + TARGET_CLASS + " not found, scanning common names...");
        }

        String[] candidates = {
            "com.xiaomi.joyose.utils.w",
            "com.xiaomi.joyose.utils.v",
            "com.xiaomi.joyose.utils.x",
            "com.xiaomi.joyose.utils.MiDFUtils"
        };

        for (String c : candidates) {
            try {
                Class<?> cls = classLoader.loadClass(c);
                if (hasDfSignatures(cls)) {
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
     * Hook f, g, and d in MiDFUtils (com.xiaomi.joyose.utils.w)
     */
    private void hookMiDFUtils(Class<?> targetClass) {
        // 1. Hook w.f(Context, String pkg, float fps, int reason)
        try {
            XposedHelpers.findAndHookMethod(
                targetClass,
                "f",
                Context.class,
                String.class,
                float.class,
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        String pkg = (String) param.args[1];
                        float fps = (Float) param.args[2];
                        int reason = (Integer) param.args[3];

                        if (pkg != null && fps > 0.0f) {
                            param.setResult(null);
                            log(">>> Intercepted & Blocked w.f -> pkg=" + pkg + ", targetFps=" + fps + ", reason=" + reason);
                        } else {
                            log("Pass-through w.f (restore/clear): pkg=" + pkg + ", fps=" + fps);
                        }
                    }
                }
            );
            log("Hooked method w.f(Context, String, float, int)");
        } catch (Throwable t) {
            logError("Failed to hook w.f", t);
        }

        // 2. Hook w.g(Context, String pkg, int fps, int reason)
        try {
            XposedHelpers.findAndHookMethod(
                targetClass,
                "g",
                Context.class,
                String.class,
                int.class,
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        String pkg = (String) param.args[1];
                        int fps = (Integer) param.args[2];
                        int reason = (Integer) param.args[3];

                        if (pkg != null && fps > 0) {
                            param.setResult(null);
                            log(">>> Intercepted & Blocked w.g -> pkg=" + pkg + ", targetFps=" + fps + ", reason=" + reason);
                        } else {
                            log("Pass-through w.g (restore/clear): pkg=" + pkg + ", fps=" + fps);
                        }
                    }
                }
            );
            log("Hooked method w.g(Context, String, int, int)");
        } catch (Throwable t) {
            logError("Failed to hook w.g", t);
        }

        // 3. Hook w.d() -> int (reads /data/system/mcd/df, returns -1 if no limit)
        try {
            XposedHelpers.findAndHookMethod(
                targetClass,
                "d",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        param.setResult(-1);
                    }
                }
            );
            log("Hooked method w.d() -> always return -1 (no frame cap)");
        } catch (Throwable t) {
            logError("Failed to hook w.d", t);
        }
    }

    /**
     * Clear /data/system/mcd/df if leftover limit exists
     */
    private void clearMcdDfFile() {
        try {
            File df = new File("/data/system/mcd/df");
            if (df.exists() && df.length() > 0) {
                new FileOutputStream(df).close();
                log("Successfully cleared stale /data/system/mcd/df (truncated to 0 bytes)");
            }
        } catch (Throwable t) {
            // normal
        }
    }
}
