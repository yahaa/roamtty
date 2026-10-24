# Recent usage ordering — 2026-10-23

Base: PR #218, `72500724035f8c2108bf9c48eb95dcb902056280` (`feat/remote-desktop-platforms`). The implementation is on `feat/recent-used-lists` in an isolated worktree.

## Results

- JVM suites: **611 passed, 0 failures, 33 skipped** across app, ssh-core, desktop-core, terminal-emulator and terminal-view.
- Device tests on emulator-5554 (API 36), using isolated package `com.roamtty.app.recencytest`: **10 passed**. Covers session/host navigation, activity recreation, unchanged scroll position, background reconnect, persistence/pruning, group/filter order, cold service restoration, host cards and both existing HomePageTest cases.
- `:app:lintDebug`: passed (existing nonfatal warnings remain).
- `:app:assembleRelease`: passed with R8 and resource shrinking.
- APK alignment, package/version and v2/v3 signatures verified. Signing certificate matches the existing PR #218 test APK.

The initial host-order test failed on the baseline at its ordering assertion. Independent review found an unconditional host scroll reset; the added recreation test reproduced it. Fixing that also required preserving the scroll offset in the existing orientation effect. Cold service restoration is tested separately, before any activity or service is launched.

## Environment and follow-up fixes

Installed Paramiko 5.1.0 and PowerShell 7.4.7 under the worktree's ignored `.local/` directory. Ran JVM tests with that Python/Powershell on PATH and `ROAMTTY_PWSH` set. The remaining 33 skipped tests require dedicated OpenSSH/SFTP/mosh or native Windows/desktop-plugin fixtures, or explicitly enabled benchmarks. They are **not counted as passed**; real-device Windows/UDP behavior is outside this list-ordering validation.

Enabling the SSH fixtures reproduced `missingTmuxStopsReattachment` twice: `findMultiplexer` reused a previous login's result and never noticed tmux had disappeared. It now probes each fresh login and clears unavailable metadata; the existing regression and full suite pass.

Validation also exposed two baseline lint errors: desktop refresh-rate lookup used an API-30 Context method despite minSdk 26, and the session desktop tag read StateFlow.value in composition. The current View's display and lifecycle-aware flow collection resolve them. HomePageTest's obsolete plain-terminal heading assertion now checks the existing folder selector and launch action.

## APK

- File: `dist/Roamtty-recent-used-lists-20261023-release-debugsigned.apk` (in the primary checkout).
- Package: `com.roamtty.app`; version: `0.1.0-recent-used-20261023`; versionCode: `100`; minSdk: `26`.
- Release build, signed with the existing Android debug certificate for local testing.
- SHA-256: `90f454753cefc25d8966390905a036b65cac5ad38bf812145e9b65f03b8c6bcd`.
