#!/bin/bash
# AgentDeck APK 构建脚本：javac -> R8/D8 -> aapt2 -> zipalign -> apksigner
set -e
cd "$(dirname "$0")"

SDK=${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}
BT=$SDK/build-tools/34.0.0
PLAT=$SDK/platforms/android-34/android.jar
JH=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}
export JAVA_HOME=$JH

VER_CODE=$(grep -o 'versionCode="[0-9]*"' AndroidManifest.xml >/dev/null 2>&1; echo "")
OUT=${1:-build/agentdeck.apk}

echo "[1/6] 编译 Java"
rm -rf build/classes build/dex && mkdir -p build/classes build/dex
"$JH/bin/javac" --release 11 -d build/classes -classpath "$PLAT" java/com/agentdeck/desk/*.java 2>&1 | grep -vE "过时的|Xlint|某些" || true

echo "[2/6] 生成 dex"
# build-tools 自带的 d8 对 JDK21 编出的字节码有 bug，用 Google Maven 的 R8
R8_JAR=${R8_JAR:-/tmp/r8-8.2.jar}
if [ ! -s "$R8_JAR" ]; then
  curl -sL -o "$R8_JAR" "https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.2.42/r8-8.2.42.jar"
fi
"$JH/bin/java" -cp "$R8_JAR" com.android.tools.r8.D8 --release --min-api 21 --lib "$PLAT" \
  --output build/dex build/classes/com/agentdeck/desk/*.class

echo "[3/6] 打包资源与清单"
rm -rf build/pkg && mkdir -p build/pkg
$BT/aapt2 link -I "$PLAT" --manifest AndroidManifest.xml -o build/pkg/base.apk \
  --min-sdk-version 21 --target-sdk-version 29 --version-code 9 --version-name 1.5

echo "[4/6] 注入 dex"
cp build/pkg/base.apk build/app-unaligned.apk
(cd build/dex && /usr/bin/zip -j -q ../app-unaligned.apk classes.dex)

echo "[5/6] zipalign"
$BT/zipalign -f 4 build/app-unaligned.apk build/app-aligned.apk

echo "[6/6] 签名"
if [ ! -f build/debug.keystore ]; then
  "$JH/bin/keytool" -genkeypair -keystore build/debug.keystore -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android \
    -dname "CN=AgentDeck, O=Dev, C=CN" >/dev/null 2>&1
fi
$BT/apksigner sign --ks build/debug.keystore --ks-key-alias androiddebugkey \
  --ks-pass pass:android --key-pass pass:android --out "$OUT" build/app-aligned.apk

echo "✓ 输出: $OUT"
