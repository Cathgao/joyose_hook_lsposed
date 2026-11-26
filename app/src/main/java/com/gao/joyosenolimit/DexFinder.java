package com.gao.joyosenolimit;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Lightweight pure-Java DEX anchor scanner with APK-fingerprint caching.
 * Only executes when fast-path candidate class names fail (e.g. heavy R8 obfuscation
 * or new Joyose versions), adding zero overhead during normal gameplay.
 */
final class DexFinder {

    private static final String CACHE_FILE_NAME = "joyose_nolimit_cache.txt";
    private static final byte[] DF_PATH_BYTES = "/data/system/mcd/df".getBytes(StandardCharsets.UTF_8);

    static final class MatchResult {
        final String className;
        final String readerMethodName; // nullable

        MatchResult(String className, String readerMethodName) {
            this.className = className;
            this.readerMethodName = readerMethodName;
        }
    }

    private DexFinder() {
    }

    static String readCachedClassName(Context context, String apkPath) {
        if (context == null || apkPath == null) return null;
        String stamp = getApkStamp(apkPath);
        if (stamp == null) return null;

        File cacheFile = new File(context.getFilesDir(), CACHE_FILE_NAME);
        if (!cacheFile.exists()) return null;

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(cacheFile), StandardCharsets.UTF_8))) {
            String cachedStamp = br.readLine();
            String cachedClass = br.readLine();
            if (stamp.equals(cachedStamp) && cachedClass != null && !cachedClass.trim().isEmpty()) {
                return cachedClass.trim();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    static void writeCachedClassName(Context context, String apkPath, String className) {
        if (context == null || apkPath == null || className == null) return;
        String stamp = getApkStamp(apkPath);
        if (stamp == null) return;

        File cacheFile = new File(context.getFilesDir(), CACHE_FILE_NAME);
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(cacheFile, false), StandardCharsets.UTF_8)) {
            writer.write(stamp + "\n" + className + "\n");
            writer.flush();
        } catch (Throwable ignored) {
        }
    }

    private static String getApkStamp(String apkPath) {
        try {
            File f = new File(apkPath);
            if (!f.exists()) return null;
            return f.lastModified() + ":" + f.length();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Scans classes*.dex inside the target APK for classes referencing "/data/system/mcd/df"
     * and MiDFUtils hint strings. Returns strong matches first, followed by df-only candidates.
     */
    static List<MatchResult> findMiDFCandidates(String apkPath) {
        List<MatchResult> strong = new ArrayList<>();
        List<MatchResult> weak = new ArrayList<>();
        if (apkPath == null) return strong;

        try (ZipFile zip = new ZipFile(apkPath)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("classes") && name.endsWith(".dex") && !name.contains("/")) {
                    byte[] dexBytes = readEntry(zip, entry);
                    if (dexBytes != null) {
                        scanDex(dexBytes, strong, weak);
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        strong.addAll(weak);
        return strong;
    }

    private static byte[] readEntry(ZipFile zip, ZipEntry entry) {
        long size = entry.getSize();
        if (size <= 0 || size > 32 * 1024 * 1024) return null;
        byte[] buf = new byte[(int) size];
        try (InputStream is = zip.getInputStream(entry)) {
            int offset = 0;
            while (offset < buf.length) {
                int read = is.read(buf, offset, buf.length - offset);
                if (read < 0) break;
                offset += read;
            }
            return offset == buf.length ? buf : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void scanDex(byte[] dex, List<MatchResult> strong, List<MatchResult> weak) {
        if (dex.length < 0x70) return;
        ByteBuffer bb = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN);

        int stringIdsSize = bb.getInt(0x38);
        int stringIdsOff = bb.getInt(0x3c);
        int typeIdsSize = bb.getInt(0x40);
        int typeIdsOff = bb.getInt(0x44);
        int methodIdsSize = bb.getInt(0x58);
        int methodIdsOff = bb.getInt(0x5c);
        int classDefsSize = bb.getInt(0x60);
        int classDefsOff = bb.getInt(0x64);

        if (stringIdsSize <= 0 || classDefsSize <= 0) return;

        // 1. Locate anchor string indices with minimal allocations
        int dfStrIdx = -1;
        boolean[] isHintStr = new boolean[stringIdsSize];
        boolean[] isReaderHintStr = new boolean[stringIdsSize];
        boolean hasAnyAnchor = false;

        for (int i = 0; i < stringIdsSize; i++) {
            int strDataOff = bb.getInt(stringIdsOff + i * 4);
            int p = skipUleb128(dex, strDataOff);
            int len = 0;
            while (p + len < dex.length && dex[p + len] != 0) {
                len++;
            }
            if (len == DF_PATH_BYTES.length && bytesEqual(dex, p, DF_PATH_BYTES)) {
                dfStrIdx = i;
                hasAnyAnchor = true;
            } else if (len >= 9 && len <= 64) {
                String s = new String(dex, p, len, StandardCharsets.UTF_8);
                if (s.contains("MiDFUtils") || s.contains("writeToFile skip low fps")) {
                    isHintStr[i] = true;
                    hasAnyAnchor = true;
                } else if (s.contains("readFpsInFile")) {
                    isHintStr[i] = true;
                    isReaderHintStr[i] = true;
                    hasAnyAnchor = true;
                }
            }
        }

        if (!hasAnyAnchor || dfStrIdx < 0) {
            return;
        }

        // 2. Scan class_defs bytecode for const-string references to anchors
        int[] cursor = new int[1];
        for (int i = 0; i < classDefsSize; i++) {
            int defBase = classDefsOff + i * 32;
            int classIdx = bb.getInt(defBase);
            int classDataOff = bb.getInt(defBase + 24);
            if (classDataOff == 0 || classIdx < 0 || classIdx >= typeIdsSize) continue;

            cursor[0] = classDataOff;
            int sfSize = readUleb128(dex, cursor);
            int ifSize = readUleb128(dex, cursor);
            int dmSize = readUleb128(dex, cursor);
            int vmSize = readUleb128(dex, cursor);

            for (int f = 0; f < sfSize + ifSize; f++) {
                readUleb128(dex, cursor);
                readUleb128(dex, cursor);
            }

            boolean classUsesDf = false;
            boolean classUsesHint = false;
            String readerMethod = null;

            for (int grp = 0; grp < 2; grp++) {
                int count = (grp == 0) ? dmSize : vmSize;
                int methodIdx = 0;
                for (int m = 0; m < count; m++) {
                    methodIdx += readUleb128(dex, cursor);
                    readUleb128(dex, cursor); // accessFlags
                    int codeOff = readUleb128(dex, cursor);
                    if (codeOff == 0 || codeOff + 16 > dex.length) continue;

                    int insnsSize = bb.getInt(codeOff + 12);
                    int insnsStart = codeOff + 16;
                    if (insnsSize <= 0 || insnsStart + insnsSize * 2 > dex.length) continue;

                    int flags = scanCodeItem(bb, insnsStart, insnsSize, dfStrIdx, isHintStr, isReaderHintStr);
                    if ((flags & 1) != 0) classUsesDf = true;
                    if ((flags & 2) != 0) classUsesHint = true;
                    if (readerMethod == null && (flags & 4) != 0) {
                        if (methodIdx >= 0 && methodIdx < methodIdsSize) {
                            int nameIdx = bb.getInt(methodIdsOff + methodIdx * 8 + 4);
                            readerMethod = readDexString(dex, bb, stringIdsOff, nameIdx);
                        }
                    }
                }
            }

            if (classUsesDf) {
                int typeStrIdx = bb.getInt(typeIdsOff + classIdx * 4);
                String desc = readDexString(dex, bb, stringIdsOff, typeStrIdx);
                if (desc != null && desc.startsWith("L") && desc.endsWith(";")) {
                    String dotName = desc.substring(1, desc.length() - 1).replace('/', '.');
                    MatchResult mr = new MatchResult(dotName, readerMethod);
                    if (classUsesHint) {
                        strong.add(mr);
                    } else {
                        weak.add(mr);
                    }
                }
            }
        }
    }

    /**
     * Returns bitmask:
     * bit 0 (1): references dfStrIdx
     * bit 1 (2): references any MiDF hint string
     * bit 2 (4): references readFpsInFile
     */
    private static int scanCodeItem(
            ByteBuffer bb, int insnsStart, int insnsSize,
            int dfStrIdx, boolean[] isHintStr, boolean[] isReaderHintStr) {
        int result = 0;
        int pc = 0;
        while (pc < insnsSize) {
            int w0 = bb.getShort(insnsStart + pc * 2) & 0xffff;
            int op = w0 & 0xff;

            if (w0 == 0x0100) { // packed-switch payload
                if (pc + 1 >= insnsSize) break;
                int sz = bb.getShort(insnsStart + (pc + 1) * 2) & 0xffff;
                pc += sz * 2 + 4;
                continue;
            } else if (w0 == 0x0200) { // sparse-switch payload
                if (pc + 1 >= insnsSize) break;
                int sz = bb.getShort(insnsStart + (pc + 1) * 2) & 0xffff;
                pc += sz * 4 + 2;
                continue;
            } else if (w0 == 0x0300) { // fill-array-data payload
                if (pc + 3 >= insnsSize) break;
                int ew = bb.getShort(insnsStart + (pc + 1) * 2) & 0xffff;
                int sz = bb.getInt(insnsStart + (pc + 2) * 2);
                pc += (sz * ew + 1) / 2 + 4;
                continue;
            }

            if (op == 0x1a && pc + 1 < insnsSize) { // const-string
                int strIdx = bb.getShort(insnsStart + (pc + 1) * 2) & 0xffff;
                if (strIdx == dfStrIdx) result |= 1;
                if (strIdx < isHintStr.length && isHintStr[strIdx]) result |= 2;
                if (strIdx < isReaderHintStr.length && isReaderHintStr[strIdx]) result |= 4;
            } else if (op == 0x1b && pc + 2 < insnsSize) { // const-string/jumbo
                int strIdx = bb.getInt(insnsStart + (pc + 1) * 2);
                if (strIdx == dfStrIdx) result |= 1;
                if (strIdx >= 0 && strIdx < isHintStr.length && isHintStr[strIdx]) result |= 2;
                if (strIdx >= 0 && strIdx < isReaderHintStr.length && isReaderHintStr[strIdx]) result |= 4;
            }

            int len = insnLengthUnits(op);
            if (len <= 0) break;
            pc += len;
        }
        return result;
    }

    private static int insnLengthUnits(int op) {
        if (op == 0x00 || op == 0x0e || op == 0x28) return 1;
        if (op == 0x01 || op == 0x04 || op == 0x07 || (op >= 0x0a && op <= 0x12)
                || op == 0x1d || op == 0x1e || op == 0x21 || op == 0x27
                || (op >= 0x7b && op <= 0x8f) || (op >= 0xb0 && op <= 0xcf)) return 1;
        if (op == 0x18) return 5;
        if (op == 0x03 || op == 0x06 || op == 0x09 || op == 0x14 || op == 0x17
                || op == 0x1b || (op >= 0x24 && op <= 0x26) || op == 0x2a
                || op == 0x2b || op == 0x2c || (op >= 0x6e && op <= 0x78)) return 3;
        if (op >= 0xe3) return 1;
        return 2;
    }

    private static boolean bytesEqual(byte[] data, int off, byte[] target) {
        if (off + target.length > data.length) return false;
        for (int i = 0; i < target.length; i++) {
            if (data[off + i] != target[i]) return false;
        }
        return true;
    }

    private static int skipUleb128(byte[] data, int pos) {
        while (pos < data.length && (data[pos++] & 0x80) != 0) {
        }
        return pos;
    }

    private static int readUleb128(byte[] data, int[] pos) {
        int res = 0;
        int shift = 0;
        int p = pos[0];
        while (p < data.length) {
            int b = data[p++] & 0xff;
            res |= (b & 0x7f) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        pos[0] = p;
        return res;
    }

    private static String readDexString(byte[] dex, ByteBuffer bb, int stringIdsOff, int strIdx) {
        if (strIdx < 0) return null;
        int off = bb.getInt(stringIdsOff + strIdx * 4);
        int p = skipUleb128(dex, off);
        int end = p;
        while (end < dex.length && dex[end] != 0) end++;
        return new String(dex, p, end - p, StandardCharsets.UTF_8);
    }
}
