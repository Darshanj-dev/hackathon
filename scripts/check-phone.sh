#!/usr/bin/env bash
# Developer-only diagnostics. Does not export camera frames or recognized text.
set -euo pipefail
sdk_path="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
adb_path="$sdk_path/platform-tools/adb"
if [ ! -x "$adb_path" ]; then adb_path=adb; fi
"$adb_path" devices
"$adb_path" shell dumpsys package com.findback.app | sed -n '/versionCode=/p;/versionName=/p;/android.permission.CAMERA:/p'
"$adb_path" shell run-as com.findback.app ls -l no_backup/models/ || true
"$adb_path" logcat -d -t 500 -s Saathi:E AndroidRuntime:E
