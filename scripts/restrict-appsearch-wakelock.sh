#!/bin/bash
# Restrict com.google.android.settings.intelligence (App Search) to reduce
# AppSearchIndexUpdateJobService wakelock drain.
#
# Run via: adb root && adb shell "sh -s" < restrict-appsearch-wakelock.sh
# Or push and run: adb push restrict-appsearch-wakelock.sh /data/local/tmp/ && adb shell "sh /data/local/tmp/restrict-appsearch-wakelock.sh"
#
# Requires root (adb root).

PKG="com.google.android.settings.intelligence"

# Force app to restricted standby bucket - limits background jobs and alarms
am set-standby-bucket "$PKG" restricted 2>/dev/null && echo "OK: $PKG set to restricted bucket" || echo "Fail: need root (adb root)"

echo "To revert: am set-standby-bucket $PKG rare"
