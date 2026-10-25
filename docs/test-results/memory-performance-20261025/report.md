# Memory and performance audit — 2026-10-25

Base: freshly fetched `origin/main`, `ebbbb874f71eaefe29f8515e90e87750ff838ba9`.
Device: PLU110, Android 16 / API 36. Phone measurements use the non-debuggable,
unshrunk `e2e` build with the bundled baseline profile compiled by `speed-profile`.
The separate `com.roamtty.app.e2e` package preserves the installed application and its data.

## Reproduced retention and fixes

| Scenario | Before | After | Cause and change |
|---|---:|---:|---|
| Remove three naturally ended SSH sessions | 3 retained after repeated GC | 0 | `forget()` removed the card but left service-scoped metadata/history collectors running. Dispose the connection when forgetting it. |
| Remove three failed logins | 3 retained after repeated GC | 0 | Same service ownership path; the service remains bound during verification. |
| Close eight sessions without recreating the Activity | 1 in the sequential probe; 8 in the full-suite benchmark | 0 | Remembered `LazyGridState` retained recycled row click callbacks. Replace the grid state on the empty/nonempty boundary and update its derived scroll observer. |
| Block decoder thread, submit 128 × 64 KiB payloads | 128 payloads retained | 0 in the burst regression | Each Handler closure owned a packet. A coalesced wakeup now drains a queue limited to four encoded frames / 8 MiB of backing arrays. |
| Receive 10,000 frames without a Surface / decode callback | 10,000 timestamps | 160 | Bookkeeping was pruned only after successful decoding. Prune in batches on receipt and clear it at stream boundaries. |
| Cancel browser while link resolution is suspended | Lookup remained active | Lookup canceled; shared scope still active | Include the pending link job in browser cancellation. JVM regression reproduced the failure before the change. |

Heap dumps establish both connection ownership paths, rather than inferring leaks from PSS.
Sanitized paths are in [retention-paths.txt](retention-paths.txt); raw dumps remain local because
they contain session/runtime data. Final session regressions keep both Activity and service alive.
The previous test workaround that recreated the Activity before GC has been removed.

The video limit applies to queued encoded input, excluding codec/GPU memory and a frame already
being consumed. Overflow discards the broken dependency chain and requests a fresh keyframe;
deltas are ignored until it arrives. A new keyframe supersedes old queued frames. Detach and close
discard queued data immediately, including while the decoder thread is blocked. Codec calls and
callbacks stay outside the queue lock. This bounds memory and stale-frame latency under stalls;
it is not a claim that every frame will be delivered during overload.

## Validation

- 662 unique JVM tests passed across app, ssh-core, desktop-core, terminal-emulator and terminal-view.
  See [jvm-results.json](jvm-results.json). Full-run results and supplemental fixture runs are merged
  by class and test name, so repeated tests are not double-counted.
- Supplemental fixtures exercised SFTP transfer/preview, PowerShell scripts, fish/tcsh/Nushell,
  Linux plugin installation, Mosh recovery, and desktop UDP loss/migration/fallback.
  The Linux installer test now explicitly selects TMUX and MOSH; iterating the expanded enum also
  attempted Linux package installation of Windows-only PSMUX and the separately installed DESKTOP plugin.
- One opt-in native Windows SSH metrics test was not run: no disposable Windows fixture or credentials
  were configured, and its default `127.0.0.1:22249` refused connections. It is recorded as skipped,
  not passed. PowerShell script tests ran on Linux; these do not replace native Windows verification.
- All module JVM/lint tasks completed; the final app E2E lint and 17 focused queue/decoder/browser tests
  passed after the final production change. `git diff --check` and shell syntax validation passed.
- Additional phone lifecycle probes passed: 20 session switches retained only the current view;
  six reconnects retained no replaced connections; six Activity recreations retained no old Activities
  or terminal views; closing eight sessions retained no connections or views after the fix.

The full phone run, [full-run-results.json](full-run-results.json), passed 44/45 tests, including all
memory cases, desktop transports and landscape scrolling. The file-preview test raced asynchronous
system Back dispatch and inspected the previous directory. It now waits for the parent listing
before scrolling. A follow-up showed that waiting alone was insufficient: system Back could be consumed by the search IME or departing preview window. The test also explicitly closes the IME and waits for preview removal before Back; its final targeted result is recorded below. The same full run measured home PSS at
159.2 MiB against a 150 MiB budget; that failed budget is preserved in the evidence.

The same full workload on unmodified main retained all eight benchmark sessions after closing them.
[Baseline full results](baseline-full-results.json) preserve the complete outcome (39/41 tests passed;
plugin-sheet timing and flood navigation also failed). Its home PSS was 146.9 MiB and managed heap
with eight sessions was 83.72 MiB, versus 83.73 MiB in the fixed full run. The baseline's 253.3 MiB
post-close PSS includes the test's failure-triggered heap dump, so it is not a clean measure of bytes
saved by the fix. Weak-reference collection and heap ownership paths establish the retention result.
The earlier fixed run's home PSS budget failure is not being dismissed as a speedup or counted as a pass.

Repeating the full fixed run measured **141.8 MiB home PSS and 144.2 MiB after closing eight sessions**,
with zero retained sessions and no measured budget violations. See
[repeated-full-results.json](repeated-full-results.json). This run also passed 44/45 tests before the
last test synchronization change. The 17.4 MiB spread between two runs of the same production APK
shows why the earlier single PSS sample cannot establish a regression or an optimization percentage.
The repeated full run and the main comparison used the same feature/desktop/performance sequence;
fixture/UI timing failures are preserved, not counted as passes.

The final file-preview regression passed **three consecutive runs** after explicit IME/preview teardown,
with all preview, search, image, directory navigation and 1,500-file listing assertions intact.
Raw instrumentation results: [run 1](preview-followup-1.txt), [run 2](preview-followup-2.txt),
[run 3](preview-followup-3.txt). Across the full run and this targeted follow-up, all 45 distinct phone
cases have passed. This is a combined result, not a claim that the earlier 44/45 run was green.

## APK

`Roamtty-memory-performance-20261025-release-debugsigned.apk` is the R8-minified Release build,
signed with the existing local Android debug key. Build, APK signature and 16 KiB alignment checks passed.
Phone instrumentation exercises the corresponding E2E build, not the shrunk Release APK.
Package/version and SHA256 are in [apk.json](apk.json).

## Reproduction

```sh
./gradlew :app:testDebugUnitTest :ssh-core:test :desktop-core:test \
  :terminal-emulator:testDebugUnitTest :terminal-view:testDebugUnitTest
./gradlew :app:lintE2e :app:assembleE2e :app:assembleE2eAndroidTest \
  -PitermuxTestBuildType=e2e
ROAMTTY_DESKTOP_DIST=/path/to/roamtty-desktop/dist \
E2E_EXTRA_ARGS='-e e2eUdpHost <fixture-LAN-address>' \
scripts/e2e/run.sh -s <phone-serial> --no-unit --no-build
```

Desktop UDP tests require a LAN route to the fixture; `adb reverse` carries only TCP.
Optional JVM integrations need their documented fixtures and environment variables; an unconfigured
plain Gradle invocation will skip them. No performance thresholds were relaxed for this change.
