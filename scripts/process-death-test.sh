#!/usr/bin/env bash
# Two-process integration check against the prepared local OpenSSH fixture.
set -euo pipefail
serial=${1:?Usage: process-death-test.sh emulator-NNNN}
[[ "$serial" =~ ^emulator-[0-9]+$ ]] || { echo 'Use a disposable emulator.' >&2; exit 1; }
project_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
adb_bin="${ANDROID_HOME:?Set ANDROID_HOME}/platform-tools/adb"
runner=io.itermux.app.test/androidx.test.runner.AndroidJUnitRunner
ready=/sdcard/Android/data/io.itermux.app/files/lifecycle-ready.txt
mkdir -p "$project_root/.local/fix-validation"
"$adb_bin" -s "$serial" reverse tcp:22228 tcp:22228
"$adb_bin" -s "$serial" shell am force-stop io.itermux.app
"$adb_bin" -s "$serial" shell rm -f "$ready"
"$adb_bin" -s "$serial" shell am instrument -w -e class io.itermux.app.ProcessDeathTest#prepare \
  -e lifecycleStage prepare "$runner" > "$project_root/.local/fix-validation/process-prepare.log" 2>&1 &
driver_pid=$!
cleanup() {
  "$adb_bin" -s "$serial" shell am force-stop io.itermux.app
  wait "$driver_pid" 2>/dev/null || true
}
trap cleanup EXIT
seen=false
for ((attempt=0; attempt<40; attempt++)); do
  if "$adb_bin" -s "$serial" shell test -f "$ready"; then seen=true; break; fi
  kill -0 "$driver_pid" 2>/dev/null || break
  sleep 1
done
[[ "$seen" == true ]] || { echo 'Live shell + tmux did not become ready; inspect process-prepare.log.' >&2; exit 1; }
"$adb_bin" -s "$serial" shell am force-stop io.itermux.app
wait "$driver_pid" || true
"$adb_bin" -s "$serial" shell am instrument -w -e class io.itermux.app.ProcessDeathTest#recover \
  -e lifecycleStage recover "$runner" | tee "$project_root/.local/fix-validation/process-recover.log"
rg -q 'OK \(1 test\)' "$project_root/.local/fix-validation/process-recover.log"
echo 'PASS: disconnected entries/titles restored, no input replay, explicit tmux reattachment.'
