#!/usr/bin/env bash
# Tests real package discovery with a disposable GitHub handler; never replaces an installed app.
set -euo pipefail
cd "$(dirname "$0")/.."
sdk="${ANDROID_HOME:-$(sed -n 's/^sdk.dir=//p' local.properties)}"
build_tools="$sdk/build-tools/36.0.0"
device="${1:-emulator-5554}"
[[ "$device" == emulator-* ]] || { echo 'Use an isolated Android emulator.' >&2; exit 1; }
adb=("$sdk/platform-tools/adb" -s "$device")
if "${adb[@]}" shell pm path com.github.android | rg -q '^package:'; then
    echo 'GitHub is already installed; use an emulator without it.' >&2
    exit 1
fi
mkdir -p .local
results=$(mktemp -d "$PWD/.local/github-links.XXXXXX")
fixture_installed=false
cleanup() {
    if "$fixture_installed"; then
        "${adb[@]}" shell pm unsuspend com.github.android >/dev/null 2>&1 || true
        "${adb[@]}" uninstall com.github.android >/dev/null
    fi
}
trap cleanup EXIT
mkdir -p "$results/classes" "$results/dex"
javac --release 8 -cp "$sdk/platforms/android-36/android.jar" -d "$results/classes" scripts/fixtures/github-links/LinkFixture.java
"$build_tools/d8" --lib "$sdk/platforms/android-36/android.jar" --output "$results/dex" "$results/classes/com/github/android/LinkFixture.class"
"$build_tools/aapt" package -f -M scripts/fixtures/github-links/AndroidManifest.xml -I "$sdk/platforms/android-36/android.jar" -F "$results/base.apk"
zip -j "$results/base.apk" "$results/dex/classes.dex" >/dev/null
"$build_tools/zipalign" -f 4 "$results/base.apk" "$results/aligned.apk"
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --console=plain > "$results/build.log" 2>&1
"$build_tools/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "$results/fixture.apk" "$results/aligned.apk"
"${adb[@]}" install -r app/build/outputs/apk/debug/app-debug.apk
"${adb[@]}" install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"${adb[@]}" install "$results/fixture.apk"
fixture_installed=true
run_tests() {
    "${adb[@]}" shell am instrument -w -r -e class io.itermux.app.GitHubLinkTest -e githubExpected "$2" \
        com.roamtty.app.test/io.itermux.app.ItermuxTestRunner > "$results/$1.log" 2>&1
    if ! rg -q 'OK \(4 tests\)' "$results/$1.log" || rg -q '^INSTRUMENTATION_STATUS_CODE: -' "$results/$1.log"; then
        cat "$results/$1.log"
        exit 1
    fi
    echo "$1: 4 passed, 0 failed/skipped"
}
"${adb[@]}" shell am start -S -W -n com.github.android/.LinkFixture --ez linksEnabled true > "$results/enable-alias.log"
run_tests available app
"${adb[@]}" shell am start -S -W -n com.github.android/.LinkFixture --ez linksEnabled false > "$results/disable-alias.log"
run_tests component_disabled browser
"${adb[@]}" shell am start -S -W -n com.github.android/.LinkFixture --ez linksEnabled true >> "$results/enable-alias.log"
"${adb[@]}" shell pm suspend com.github.android
run_tests suspended browser
"${adb[@]}" shell pm unsuspend com.github.android
"${adb[@]}" shell pm disable-user --user 0 com.github.android
run_tests disabled browser
"${adb[@]}" uninstall com.github.android
fixture_installed=false
run_tests absent browser
echo "GitHub link matrix passed. Logs: $results"
