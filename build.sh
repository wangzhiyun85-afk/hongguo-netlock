#!/bin/bash
# 独立构建红果断网 APK
set -e
cd "$(dirname "$0")"
export ANDROID_HOME=${ANDROID_HOME:-/opt/android-sdk}
export ANDROID_SDK_ROOT=$ANDROID_HOME
./gradlew :netlock:assembleRelease --no-daemon --offline
version=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' netlock/build.gradle.kts | head -1)
out="apk/jingxuan-netlock-${version}.apk"
cp netlock/build/outputs/apk/release/netlock-release.apk "$out"
echo "APK: $(pwd)/$out"
