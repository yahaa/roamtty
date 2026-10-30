#!/usr/bin/env bash
# Systematic end-to-end run of Roamtty on one device: unit tests, every feature through the real UI against a
# real OpenSSH/SFTP server, and performance samples checked against scripts/e2e/budgets.json.
#
#   scripts/e2e/run.sh [-s SERIAL] [--no-unit] [--no-build] [--only E2eFeatureTest|E2ePerfTest|Class#method]
#   E2E_EXTRA_ARGS="-e winHost … -e winPassword …" adds instrumentation arguments (E2eWindowsServiceTest needs a Windows PC).
#
# On a phone, add E2E_EXTRA_ARGS="-e e2eUdpHost <fixture-LAN-IP>" for desktop UDP tests; adb reverse only forwards TCP.
# Safe on a personal phone: it installs a separate package (com.roamtty.app.e2e, the `e2e` build type) and never
# installs, uninstalls or opens the installed Roamtty (com.roamtty.app) or its sessions. The phone must be unlocked;
# the suite takes over the screen for about ten minutes. Needs sudo for the loopback fixture (scripts/e2e/fixture.sh).
# Output: docs/test-results/e2e-<date>-<device>/ with report.md, summary.json, metrics.jsonl and screenshots.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
SERIAL="${ANDROID_SERIAL:-}"; UNIT=1; BUILD=1; ONLY=""
while [ $# -gt 0 ]; do
  case "$1" in
    -s|--serial) SERIAL="$2"; shift 2 ;;
    --no-unit) UNIT=0; shift ;;
    --no-build) BUILD=0; shift ;;
    --only) ONLY="$2"; shift 2 ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "Unknown option $1" >&2; exit 2 ;;
  esac
done
SDK="$(sed -n 's/^sdk.dir=//p' local.properties 2>/dev/null || true)"
ADB="$(command -v adb || echo "$SDK/platform-tools/adb")"
if [ -z "$SERIAL" ]; then
  mapfile -t devices < <("$ADB" devices | awk 'NR > 1 && $2 == "device" { print $1 }')
  [ "${#devices[@]}" -eq 1 ] || { echo "Pick a device with -s (connected: ${devices[*]:-none})." >&2; exit 2; }
  SERIAL="${devices[0]}"
fi
adb() { "$ADB" -s "$SERIAL" "$@"; }
PKG=com.roamtty.app.e2e
RUNNER=$PKG.test/io.itermux.app.ItermuxTestRunner
PORT=${ROAMTTY_E2E_PORT:-22258}

[ "$(adb get-state 2>/dev/null)" = device ] || { echo "$SERIAL is not connected." >&2; exit 2; }
# An idle emulator turns its screen off, and a sleeping screen reports no launch times.
adb shell input keyevent KEYCODE_WAKEUP
if adb shell dumpsys window | grep -qE 'mDreamingLockscreen=true|isKeyguardShowing=true|mShowingLockscreen=true'; then
  echo "$SERIAL is locked. Unlock it and run again." >&2; exit 2
fi
MODEL="$(adb shell getprop ro.product.model | tr -d '\r')"
SDK_INT="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
HARDWARE="$(adb shell getprop ro.hardware | tr -d '\r')"
case "$HARDWARE" in ranchu|goldfish) PROFILE=emulator ;; *) PROFILE=device ;; esac
SLUG="$(echo "$MODEL" | tr -c 'A-Za-z0-9\n' '-' | tr 'A-Z' 'a-z')"
OUT="$ROOT/docs/test-results/e2e-$(date +%Y%m%d-%H%M)-$SLUG"
mkdir -p "$OUT"
echo "== Roamtty e2e on $MODEL (API $SDK_INT, $SERIAL, profile $PROFILE) → ${OUT#$ROOT/}"
printf 'Device: %s · Android API %s · %s · commit %s · %s\n' "$MODEL" "$SDK_INT" "$SERIAL" \
  "$(git rev-parse --short HEAD)" "$(date '+%Y-%m-%d %H:%M')" > "$OUT/device.txt"
: > "$OUT/metrics.jsonl"
host_metric() { printf '{"name":"%s","value":%s,"unit":"%s"}\n' "$1" "$2" "$3" >> "$OUT/metrics.jsonl"; }

echo "== Fixture"
"$ROOT/scripts/e2e/fixture.sh" stop >/dev/null 2>&1 || true
"$ROOT/scripts/e2e/fixture.sh" start
read -r KEY_TYPE KEY_BLOB _ < /tmp/roamtty-e2e/host_ed25519.pub

if [ "$UNIT" = 1 ]; then
  echo "== Unit tests (JVM)"
  if ./gradlew -q :app:testDebugUnitTest :ssh-core:test :desktop-core:test :terminal-emulator:testDebugUnitTest :terminal-view:testDebugUnitTest \
      > "$OUT/unit-tests.log" 2>&1; then
    echo "unit tests: passed"; host_metric unit_tests_failed 0 count
  else
    echo "unit tests: FAILED (see unit-tests.log)"; host_metric unit_tests_failed 1 count
  fi
fi

if [ "$BUILD" = 1 ]; then
  echo "== Build (e2e build type: release code, not debuggable, own package)"
  ./gradlew -q :app:assembleE2e :app:assembleE2eAndroidTest -PitermuxTestBuildType=e2e > "$OUT/build.log" 2>&1 \
    || { tail -30 "$OUT/build.log"; exit 1; }
fi
APK=app/build/outputs/apk/e2e/app-e2e.apk
TEST_APK=app/build/outputs/apk/androidTest/e2e/app-e2e-androidTest.apk

echo "== Install $PKG (the installed com.roamtty.app is left alone)"
adb install -r -t "$APK" >/dev/null
adb install -r -t "$TEST_APK" >/dev/null
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb reverse tcp:$PORT tcp:$PORT >/dev/null
adb shell rm -rf /sdcard/Android/data/$PKG/files/e2e
# Compile with the bundled baseline profile, as a Play install does, so numbers do not depend on JIT warm-up.
# A sideloaded APK gets that profile only once the app's ProfileInstaller writes it; compiling before then falls back
# to `verify` and leaves everything interpreted. Drop profiles from earlier runs, have it install the bundled one now.
adb shell cmd package compile --reset $PKG >/dev/null 2>&1 || true
adb shell am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE \
  -n $PKG/androidx.profileinstaller.ProfileInstallReceiver >/dev/null 2>&1 || true
sleep 2
adb shell am force-stop $PKG
adb shell cmd package compile -m speed-profile -f $PKG >/dev/null 2>&1 || true
echo "dexopt: $(adb shell pm art dump $PKG 2>/dev/null | grep -o 'status=[a-z-]*' | head -1)"

echo "== Cold start"
adb shell am force-stop $PKG
adb shell am start -W -n $PKG/io.itermux.app.MainActivity >/dev/null # first launch: warm the file cache
starts=()
for _ in 1 2 3 4 5 6; do
  adb shell am force-stop $PKG; sleep 1
  t=$(adb shell am start -W -n $PKG/io.itermux.app.MainActivity | tr -d '\r' | sed -n 's/^TotalTime: //p')
  [ -n "$t" ] && starts+=("$t")
done
adb shell am force-stop $PKG
[ "${#starts[@]}" -gt 0 ] || { echo "No launch times: is the screen on?" >&2; exit 1; }
sorted=($(printf '%s\n' "${starts[@]}" | sort -n))
echo "cold start ms: ${starts[*]}"
host_metric cold_start_p50_ms "${sorted[$(( ${#sorted[@]} / 2 ))]}" ms
host_metric cold_start_max_ms "${sorted[-1]}" ms

echo "== Instrumentation (UI features + performance)"
CLASS="io.itermux.app.e2e.E2eFeatureTest,io.itermux.app.e2e.E2eNotificationTest,io.itermux.app.e2e.E2eDesktopTest,io.itermux.app.e2e.E2ePerfTest,io.itermux.app.e2e.E2eFramesTest,io.itermux.app.e2e.E2eMemoryTest,io.itermux.app.e2e.E2eLeakSweepTest,io.itermux.app.desktop.DesktopMemoryTest"
if [ -n "$ONLY" ]; then
  CLASS="$(echo "$ONLY" | sed -E 's/(^|,)(E2e)/\1io.itermux.app.e2e.\2/g')"
fi
adb logcat -c || true
set +e
adb shell am instrument -w -r -e e2ePassword "$(cat .private/e2e-password)" -e e2ePort $PORT \
  -e e2eHostKeyType "$KEY_TYPE" -e e2eHostKey "$KEY_BLOB" -e e2eDesktopUrl "http://127.0.0.1:${ROAMTTY_E2E_DESKTOP_PORT:-8797}/" \
  -e class "$CLASS" ${E2E_EXTRA_ARGS:-} $RUNNER > "$OUT/instrument.txt" 2>&1
set -e
adb logcat -d -s E2E:I E2E_METRIC:I AndroidRuntime:E > "$OUT/logcat.txt" 2>&1 || true
adb pull /sdcard/Android/data/$PKG/files/e2e "$OUT/screens" >/dev/null 2>&1 || true
if [ -f "$OUT/screens/metrics.jsonl" ]; then cat "$OUT/screens/metrics.jsonl" >> "$OUT/metrics.jsonl"; rm "$OUT/screens/metrics.jsonl"; fi
adb shell am force-stop $PKG
adb reverse --remove tcp:$PORT >/dev/null 2>&1 || true

echo "== Report"
set +e
python3 "$ROOT/scripts/e2e/report.py" "$OUT" "$ROOT/scripts/e2e/budgets.json" "$PROFILE"
status=$?
set -e
echo
echo "Report: ${OUT#$ROOT/}/report.md"
exit $status
