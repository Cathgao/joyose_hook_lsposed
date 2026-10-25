#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "=== Building Joyose NoLimit LSPosed Module ==="

# 1. Locate Android SDK
if [ -z "$ANDROID_HOME" ]; then
    if [ -d "/home/gao/Android/Sdk" ]; then
        ANDROID_HOME="/home/gao/Android/Sdk"
    elif [ -d "$HOME/Android/Sdk" ]; then
        ANDROID_HOME="$HOME/Android/Sdk"
    fi
fi

if [ -z "$ANDROID_HOME" ] || [ ! -d "$ANDROID_HOME" ]; then
    echo "ERROR: ANDROID_HOME not found! Please set ANDROID_HOME."
    exit 1
fi

BUILD_TOOLS_DIR=$(find "$ANDROID_HOME/build-tools" -maxdepth 1 -mindepth 1 | sort -V | tail -n 1)
PLATFORM_DIR=$(find "$ANDROID_HOME/platforms" -maxdepth 1 -mindepth 1 -name "android-*" | sort -V | tail -n 1)

echo "Using Build-Tools: $BUILD_TOOLS_DIR"
echo "Using Platform:    $PLATFORM_DIR"

AAPT2="$BUILD_TOOLS_DIR/aapt2"
D8="$BUILD_TOOLS_DIR/d8"
ZIPALIGN="$BUILD_TOOLS_DIR/zipalign"
APKSIGNER="$BUILD_TOOLS_DIR/apksigner"
ANDROID_JAR="$PLATFORM_DIR/android.jar"

# 2. Prepare build directory
BUILD_DIR="$SCRIPT_DIR/build"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/gen" "$BUILD_DIR/classes" "$BUILD_DIR/stubs_classes" "$BUILD_DIR/compiled_res" "$BUILD_DIR/apk"

# 3. Compile resources
echo "[1/5] Compiling resources with aapt2..."
"$AAPT2" compile --dir "$SCRIPT_DIR/app/src/main/res" -o "$BUILD_DIR/compiled_res.zip"
"$AAPT2" link "$BUILD_DIR/compiled_res.zip" \
    -I "$ANDROID_JAR" \
    --manifest "$SCRIPT_DIR/app/src/main/AndroidManifest.xml" \
    --java "$BUILD_DIR/gen" \
    -o "$BUILD_DIR/base_res.apk"

# 4. Compile stubs separately (compileOnly - NOT bundled into DEX!)
echo "[2/5] Compiling Xposed stubs (compileOnly)..."
javac -encoding UTF-8 \
    -cp "$ANDROID_JAR" \
    -d "$BUILD_DIR/stubs_classes" \
    $(find "$SCRIPT_DIR/stubs/src" -name "*.java")

# 5. Compile App classes (referencing stubs, but not outputting stubs into classes)
echo "[3/5] Compiling App Java sources with javac..."
javac -encoding UTF-8 \
    -cp "$ANDROID_JAR:$BUILD_DIR/stubs_classes" \
    -d "$BUILD_DIR/classes" \
    $(find "$SCRIPT_DIR/app/src/main/java" -name "*.java") \
    $(find "$BUILD_DIR/gen" -name "*.java")

# Verify classes directory does NOT contain de/robv/android/xposed
rm -rf "$BUILD_DIR/classes/de"

# 6. Convert to DEX
echo "[4/5] Compiling classes to DEX with d8..."
"$D8" --min-api 26 \
    --output "$BUILD_DIR/apk" \
    $(find "$BUILD_DIR/classes" -name "*.class")

# 7. Package APK
echo "[5/5] Packaging APK..."
cp "$BUILD_DIR/base_res.apk" "$BUILD_DIR/unaligned.apk"
cd "$BUILD_DIR/apk"
zip -u "$BUILD_DIR/unaligned.apk" classes.dex
cd "$SCRIPT_DIR/app/src/main"
zip -u "$BUILD_DIR/unaligned.apk" assets/xposed_init
cd "$SCRIPT_DIR"

# 8. Align and Sign
echo "Aligning and signing APK..."
"$ZIPALIGN" -f -p 4 "$BUILD_DIR/unaligned.apk" "$BUILD_DIR/aligned.apk"

"$APKSIGNER" sign \
    --ks "$SCRIPT_DIR/key.jks" \
    --ks-pass pass:123456 \
    --key-pass pass:123456 \
    --out "$SCRIPT_DIR/JoyoseNoLimit.apk" \
    "$BUILD_DIR/aligned.apk"

echo "=== Build Successful! ==="
echo "Output: $SCRIPT_DIR/JoyoseNoLimit.apk"
ls -lh "$SCRIPT_DIR/JoyoseNoLimit.apk"
