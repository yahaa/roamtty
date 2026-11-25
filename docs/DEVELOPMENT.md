# Development & Testing

## Toolchain

- **JDK 17** (URLs & checksums in `toolchains.lock.json`)
- **Android SDK Platform 36** & **build-tools 36.0.0** (AGP 8.10.1, Kotlin 2.1.21)
- Windows helpers: `scripts/bootstrap.ps1`, `scripts/install-sdk.ps1`, `scripts/build.ps1`

## Build & Unit Tests

```sh
# Debug build + lint
./gradlew :app:assembleDebug :app:lint

# All JVM unit tests
./gradlew :app:testDebugUnitTest :ssh-core:test :desktop-core:test :code-core:test \
  :terminal-emulator:testDebugUnitTest :terminal-view:testDebugUnitTest
```

Useful Gradle flags:
- `-PitermuxVersionName=<name>` — override test build version label
- `-PitermuxApplicationId=<id>` — install side-by-side under a separate package ID
- `-PitermuxVersionCode=<code>` — temporarily override derived `versionCode`

Release builds enable R8 code & resource shrinking (`app/proguard-rules.pro`).

## End-to-End Regression

Run the full feature + performance budget suite on an emulator or unlocked device (~10 min):

```sh
scripts/e2e/run.sh -s <DEVICE_SERIAL>
```

- Uses an isolated `e2e` build type (`com.roamtty.app.e2e`, release code, non-debuggable) so installed Roamtty data stays intact.
- Starts a loopback OpenSSH + SFTP + tmux + mock AI agent fixture on port `22258` via `scripts/e2e/fixture.sh` (requires `sudo`; hides `mosh-server` because `adb reverse` is TCP-only).
- Evaluates latency, scroll frames, idle CPU, memory/leak, and SFTP budgets from `scripts/e2e/budgets.json` and writes reports to `docs/test-results/e2e-<timestamp>-<device>/report.md`.
- Options: `--only E2eFeatureTest#<testName>`, `--no-unit`, `--no-build`.

## Device & Fixture Suites

| Suite | Setup & Command |
| --- | --- |
| **All connected tests** | `./gradlew :app:connectedDebugAndroidTest` (skips suites missing fixtures) |
| **OpenSSH fixture round** (port `22228`) | `scripts/prepare-openssh-test.sh` (root), start `host-system-server.py` on `22238`, `adb reverse` both ports, run with `-e fixturePassword "$(cat .private/test-password)" -e notClass <key-echo classes>`. Cleanup: `scripts/cleanup-openssh-test.sh`. |
| **key-echo fixture round** (port `22228`) | Stop sshd on `22228`, run `PYTHONPATH=.local/py-deps python3 scripts/ssh-key-echo.py` (password `test-only`, SFTP root `.local/sftp-fixture`), run key-echo test classes without `fixturePassword`. |
| **Windows shell & monitor** | `ROAMTTY_PWSH=/path/to/pwsh ./gradlew :app:testDebugUnitTest --tests '*WindowsShellTest' --tests '*WindowsHostMetricsTest'` |
| **Windows UI fixture** (port `22248`) | Run `scripts/windows-launch-fixture.py`, `adb reverse tcp:22248 tcp:22248`, pass `-e windowsLaunchFixture=true` to `WindowsLaunchSheetTest` / `WindowsHostMonitorTest`. |
| **Native Windows monitor** (port `22249`) | Set `ROAMTTY_WINDOWS_PASSWORD_FILE`, `ROAMTTY_WINDOWS_HOST`, `ROAMTTY_WINDOWS_PORT`, `ROAMTTY_WINDOWS_USER` for `WindowsHostMetricsIntegrationTest`; Android tests use `-e windowsNativeMonitor=true` with isolated test accounts `roamtty_admin` / `roamtty_monitor`. |
| **GitHub link handling** | `scripts/test-github-links.sh emulator-5554` (uses an isolated emulator without the GitHub app) |
| **Process death recovery** | `scripts/process-death-test.sh` |

> Test keys and fixture passwords are public test material — never reuse them on real servers.

## Release Signing

- Maintain `itermuxVersionName` in `gradle.properties`; `versionCode` derives as `MAJOR*10000 + MINOR*100 + PATCH` (`0.1.6` → `106`). Increment before every Google Play upload.
- Run `scripts/release.sh` (Linux/macOS) or `scripts/release.ps1` (Windows) to build and verify:
  - `dist/Roamtty-<version>.apk` (direct install)
  - `dist/Roamtty-<version>.aab` (Google Play)
  - `dist/SHA256SUMS.txt`
- Release key: 3072-bit RSA at `.private/signing/itermux-release.p12` with gitignored `signing.properties`. `release.sh` requires an existing key unless `ITERMUX_CREATE_RELEASE_KEY=1` is set. Back up both separately — losing the key prevents in-place upgrades.
