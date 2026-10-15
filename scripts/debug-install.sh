#!/usr/bin/env bash
# 一键真机调试：构建 debug APK → 安装 → 启动历史页便于验证删除交互
# 用法：bash scripts/debug-install.sh
set -e
cd "$(dirname "$0")/.."
export ANDROID_HOME="$HOME/android-sdk"
export JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64"
export GRADLE_USER_HOME="$HOME/.gradle"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

echo ">> 设备列表："
adb devices -l

APK=$(find app/build/outputs/apk/debug -name '*.apk' | head -1)
if [ -z "$APK" ]; then
  echo ">> 未找到 APK，先构建…"
  ./gradlew assembleDebug --no-daemon
  APK=$(find app/build/outputs/apk/debug -name '*.apk' | head -1)
fi

echo ">> 安装 $APK（-r 覆盖已装版本，保留数据）"
adb install -r "$APK"

echo ">> 启动 App（com.aicast.app）"
adb shell am start -n com.aicast.app/.MainActivity
echo ">> 完成。手机/电视上进入历史页即可验证单条删除。"
