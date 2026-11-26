package com.gao.joyosenolimit;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class HookInit implements IXposedHookLoadPackage {

    public static final String LOG_TAG = "LSPosed-JoyoseNoLimit";
    private static final String TARGET_PKG = "com.xiaomi.joyose";
    private static final String SELF_PKG = "com.gao.joyosenolimit";
    private static final String DEFAULT_TARGET_CLASS = "com.xiaomi.joyose.utils.w";
    public static final String ACTION_INTERCEPTED = "com.gao.joyosenolimit.ACTION_INTERCEPTED";
    public static final String STAT_FILE_NAME = "joyose_nolimit_stats.txt";

    private static final Object STAT_LOCK = new Object();
    private static volatile Context sJoyoseContext;
    private static volatile String sMatchedClassName;

    private static final class TargetMatch {
        final Class<?> targetClass;
        final String readerMethodName;

        TargetMatch(Class<?> targetClass, String readerMethodName) {
            this.targetClass = targetClass;
            this.readerMethodName = readerMethodName;
        }
    }

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

        // Hook JoyoseApplication.onCreate for lifecycle confirmation and fallback context capture
        try {
            XposedHelpers.findAndHookMethod(
                "com.xiaomi.joyose.JoyoseApplication",
                lpparam.classLoader,
                "onCreate",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.thisObject instanceof Context) {
                            sJoyoseContext = (Context) param.thisObject;
                            String apkPath = lpparam.appInfo != null ? lpparam.appInfo.sourceDir : null;
                            if (sMatchedClassName != null && apkPath != null) {
                                DexFinder.writeCachedClassName(sJoyoseContext, apkPath, sMatchedClassName);
                            }
                        }
                        log("JoyoseApplication.onCreate() finished. Frame-limit interception is active!");
                    }
                }
            );
        } catch (Throwable t) {
            log("Note: JoyoseApplication.onCreate not hooked (" + t.getMessage() + "), hooking sinks directly.");
        }

        // Hook Joyose frame-rate limiter sinks dynamically by anchor/signature
        try {
            TargetMatch match = findTargetClass(lpparam);
            if (match == null || match.targetClass == null) {
                logError("ERROR: Failed to find MiDFUtils class in Joyose!", null);
                return;
            }

            sMatchedClassName = match.targetClass.getName();
            hookMiDFUtils(match.targetClass, match.readerMethodName);
            log("Successfully hooked MiDFUtils (" + sMatchedClassName + ")!");

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
     * Find MiDFUtils class with fast-path candidates first, then DEX anchor scan
     * ("/data/system/mcd/df" + "MiDFUtils") for full cross-version/obfuscation resilience.
     */
    private TargetMatch findTargetClass(LoadPackageParam lpparam) {
        ClassLoader classLoader = lpparam.classLoader;
        String apkPath = lpparam.appInfo != null ? lpparam.appInfo.sourceDir : null;

        // 0. Try cached class name from Joyose's private files dir (if APK unchanged)
        if (apkPath != null && lpparam.appInfo != null && lpparam.appInfo.dataDir != null) {
            File cacheFile = new File(lpparam.appInfo.dataDir, "files/joyose_nolimit_cache.txt");
            if (cacheFile.exists()) {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(new FileInputStream(cacheFile), StandardCharsets.UTF_8))) {
                    String stamp = br.readLine();
                    String cachedCls = br.readLine();
                    File apkFile = new File(apkPath);
                    String curStamp = apkFile.lastModified() + ":" + apkFile.length();
                    if (curStamp.equals(stamp) && cachedCls != null && !cachedCls.trim().isEmpty()) {
                        Class<?> cls = classLoader.loadClass(cachedCls.trim());
                        if (hasDfSignatures(cls, false)) {
                            log("Found target class from cache: " + cachedCls.trim());
                            return new TargetMatch(cls, null);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        // 1. Fast-path: try standard / known candidate class names (<1ms)
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
                if (hasDfSignatures(cls, true)) {
                    log("Found target class with DF signatures: " + c);
                    return new TargetMatch(cls, null);
                }
            } catch (Throwable ignored) {
            }
        }

        // 2. DEX anchor scan: resilient to arbitrary obfuscation / package relocation
        if (apkPath != null) {
            List<DexFinder.MatchResult> matches = DexFinder.findMiDFCandidates(apkPath);
            for (DexFinder.MatchResult m : matches) {
                try {
                    Class<?> cls = classLoader.loadClass(m.className);
                    if (hasDfSignatures(cls, false)) {
                        log("Found target class via DEX anchor scan: " + m.className
                                + (m.readerMethodName != null ? " (reader=" + m.readerMethodName + ")" : ""));
                        return new TargetMatch(cls, m.readerMethodName);
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        // 3. Last-resort fallback: scan single/double letter classes in com.xiaomi.joyose.utils
        for (char ch = 'a'; ch <= 'z'; ch++) {
            String c = "com.xiaomi.joyose.utils." + ch;
            try {
                Class<?> cls = classLoader.loadClass(c);
                if (hasDfSignatures(cls, true)) {
                    log("Found target class via alphabet scan: " + c);
                    return new TargetMatch(cls, null);
                }
            } catch (Throwable ignored) {
            }
        }

        return null;
    }

    private boolean hasDfSignatures(Class<?> cls, boolean requireBothOverloads) {
        boolean hasFloat = false;
        boolean hasInt = false;
        for (Method m : cls.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 4 && p[0] == Context.class && p[1] == String.class) {
                if (p[2] == float.class && p[3] == int.class) hasFloat = true;
                if (p[2] == int.class && p[3] == int.class) hasInt = true;
            }
        }
        return requireBothOverloads ? (hasFloat && hasInt) : (hasFloat || hasInt);
    }

    /**
     * Dynamically hook float and int frame limit methods by parameter signature,
     * not hardcoded method names (which differ across Joyose versions: f/g vs g/h, etc.).
     */
    private void hookMiDFUtils(Class<?> targetClass, String readerMethodName) {
        int hookedCount = 0;
        List<Method> zeroArgIntMethods = new ArrayList<>();

        for (final Method method : targetClass.getDeclaredMethods()) {
            Class<?>[] p = method.getParameterTypes();

            // 1. Float limiter sink: (Context, String pkg, float fps, int reason)
            if (p.length == 4 && p[0] == Context.class && p[1] == String.class && p[2] == float.class && p[3] == int.class) {
                try {
                    XposedBridge.hookMethod(method, createLimiterHook(method, true));
                    hookedCount++;
                    log("Hooked float limiter method: " + method.getName() + "(Context, String, float, int)");
                } catch (Throwable t) {
                    logError("Failed to hook float limiter " + method.getName(), t);
                }
            }
            // 2. Integer limiter sink: (Context, String pkg, int fps, int reason)
            else if (p.length == 4 && p[0] == Context.class && p[1] == String.class && p[2] == int.class && p[3] == int.class) {
                try {
                    XposedBridge.hookMethod(method, createLimiterHook(method, false));
                    hookedCount++;
                    log("Hooked int limiter method: " + method.getName() + "(Context, String, int, int)");
                } catch (Throwable t) {
                    logError("Failed to hook int limiter " + method.getName(), t);
                }
            }
            // 3. Collect () -> int candidates to avoid hooking unrelated helper methods
            else if (p.length == 0 && method.getReturnType() == int.class) {
                zeroArgIntMethods.add(method);
            }
        }

        Method stateGetter = pickStateGetter(zeroArgIntMethods, readerMethodName);
        if (stateGetter != null) {
            try {
                XposedBridge.hookMethod(stateGetter, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        param.setResult(-1);
                    }
                });
                hookedCount++;
                log("Hooked frame status getter: " + stateGetter.getName() + "() -> always return -1");
            } catch (Throwable t) {
                logError("Failed to hook state getter " + stateGetter.getName(), t);
            }
        }

        log("Total methods hooked in " + targetClass.getName() + ": " + hookedCount);
    }

    private static XC_MethodHook createLimiterHook(final Method method, final boolean isFloat) {
        final String kind = isFloat ? "float" : "int";
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                String pkg = (String) param.args[1];
                float fps = isFloat ? (Float) param.args[2] : ((Integer) param.args[2]).floatValue();
                int reason = (Integer) param.args[3];

                if (pkg != null && fps > 0.0f) {
                    param.setResult(null);
                    clearMcdDfFile();
                    String fpsText = isFloat ? String.valueOf(fps) : String.valueOf((int) fps);
                    log(">>> Intercepted & Blocked " + method.getName() + " (" + kind + ") -> pkg=" + pkg + ", targetFps=" + fpsText + ", reason=" + reason);
                    recordInterception((Context) param.args[0], pkg, fps, reason);
                } else {
                    String fpsText = isFloat ? String.valueOf(fps) : String.valueOf((int) fps);
                    log("Pass-through " + method.getName() + " (" + kind + " restore/clear): pkg=" + pkg + ", fps=" + fpsText);
                }
            }
        };
    }

    private Method pickStateGetter(List<Method> zeroArgIntMethods, String readerMethodName) {
        if (zeroArgIntMethods.isEmpty()) return null;
        if (readerMethodName != null) {
            for (Method m : zeroArgIntMethods) {
                if (readerMethodName.equals(m.getName())) return m;
            }
        }
        if (zeroArgIntMethods.size() == 1) {
            return zeroArgIntMethods.get(0);
        }
        for (Method m : zeroArgIntMethods) {
            if ("d".equals(m.getName())) return m;
        }
        return null;
    }

    /**
     * Persist interception stats directly into Joyose's private files directory
     * (no app background process or autostart required). Also sends a
     * registered-only broadcast so an already-open MainActivity refreshes live.
     */
    private static void recordInterception(Context context, String pkg, float fps, int reason) {
        Context resolved = resolveContext(context);
        if (resolved == null) {
            logError("Failed to record interception: Context is null", null);
            return;
        }
        sJoyoseContext = resolved;

        long now = System.currentTimeMillis();
        String safePkg = pkg.replace('\n', ' ').replace('\r', ' ').trim();

        synchronized (STAT_LOCK) {
            File filesDir = resolved.getFilesDir();
            File statFile = new File(filesDir, STAT_FILE_NAME);
            int count = readExistingCount(statFile) + 1;

            String payload = count + "\n" + safePkg + "\n" + fps + "\n" + reason + "\n" + now + "\n";
            writeStatAtomically(filesDir, statFile, payload);
            log("Recorded interception #" + count + " to " + statFile.getAbsolutePath());
        }

        try {
            Intent intent = new Intent(ACTION_INTERCEPTED);
            intent.setPackage(SELF_PKG);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
            resolved.sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }

    private static Context resolveContext(Context context) {
        if (context != null) {
            Context appCtx = context.getApplicationContext();
            return appCtx != null ? appCtx : context;
        }
        return sJoyoseContext;
    }

    private static int readExistingCount(File statFile) {
        if (!statFile.exists()) {
            return 0;
        }
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(statFile), StandardCharsets.UTF_8))) {
            String firstLine = br.readLine();
            if (firstLine != null && !firstLine.trim().isEmpty()) {
                return Math.max(0, Integer.parseInt(firstLine.trim()));
            }
        } catch (Throwable t) {
            logError("Failed to read existing stat count: " + t.getMessage(), t);
        }
        return 0;
    }

    private static void writeStatAtomically(File dir, File target, String content) {
        File tmpFile = new File(dir, STAT_FILE_NAME + ".tmp");
        try {
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(tmpFile, false), StandardCharsets.UTF_8)) {
                writer.write(content);
                writer.flush();
            }
            if (!tmpFile.renameTo(target)) {
                try (OutputStreamWriter writer = new OutputStreamWriter(
                        new FileOutputStream(target, false), StandardCharsets.UTF_8)) {
                    writer.write(content);
                    writer.flush();
                }
                tmpFile.delete();
            }
        } catch (Throwable t) {
            logError("Failed to persist interception stats: " + t.getMessage(), t);
        }
    }

    /**
     * Clear /data/system/mcd/df, /data/system/mcd/dfi and legacy stat file if present
     */
    private static void clearMcdDfFile() {
        truncateIfNonEmpty("/data/system/mcd/df");
        truncateIfNonEmpty("/data/system/mcd/dfi");
        try {
            File legacy = new File("/data/system/mcd/joyose_nolimit_stat");
            if (legacy.exists()) {
                legacy.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void truncateIfNonEmpty(String path) {
        try {
            File f = new File(path);
            if (f.exists() && f.length() > 0) {
                new FileOutputStream(f).close();
                log("Successfully cleared stale " + path + " (truncated to 0 bytes)");
            }
        } catch (Throwable ignored) {
            // normal
        }
    }
}
