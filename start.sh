#!/bin/bash
# AgentDeck 一键启动：Mac 服务端 + adb reverse + 打开 App
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"

# 1. 启动采集服务端（若未运行）
if ! curl -s -m1 localhost:7777/api/health >/dev/null 2>&1; then
  echo "[1/3] 启动服务端..."
  (cd "$DIR/server" && nohup node server.js > /tmp/agentdeck.log 2>&1 &)
  sleep 1.5
fi
echo "服务端: $(curl -s -m2 localhost:7777/api/health)"

# 2. adb reverse（USB 场景手机访问 127.0.0.1:7777）
echo "[2/3] 设置 adb reverse..."
adb reverse tcp:7777 tcp:7777 2>/dev/null || true

# 3. 安装并打开 App
APK="$DIR/android/build/agentdeck.apk"
if [ -f "$APK" ]; then
  echo "[3/3] 安装并打开 App..."
  adb install -r "$APK" >/dev/null 2>&1 || true
fi
adb shell am start -n com.agentdeck.desk/.MainActivity >/dev/null 2>&1 || true
echo "✅ 完成"
