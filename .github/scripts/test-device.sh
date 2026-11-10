#!/usr/bin/env bash
set -euo pipefail

mkdir -p app/build/reports/device-screenshots
adb logcat -c
adb logcat -v time > app/build/reports/device-logcat.txt &
native_log_pid=$!
trap 'kill "$native_log_pid" 2>/dev/null || true' EXIT

native_test_status=0
./gradlew :app:connectedDebugAndroidTest --no-daemon --no-configuration-cache || native_test_status=$?
adb pull /sdcard/Android/data/com.laoke.weshirt.debug/files/. app/build/reports/device-screenshots/ || true
exit "$native_test_status"
