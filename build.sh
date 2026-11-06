#!/bin/bash
# 独立构建红果断网 APK
set -e
cd "$(dirname "$0")"
export ANDROID_HOME=${ANDROID_HOME:-/opt/android-sdk}
export ANDROID_SDK_ROOT=$ANDROID_HOME
./gradlew :netlock:assembleRelease --no-daemon --offline
cp netlock/build/outputs/apk/release/netlock-release.apk apk/jingxuan-netlock-2.7.2.apk
echo "APK: $(pwd)/apk/jingxuan-netlock-2.7.2.apk"
