# Google Play Release Checklist

Concise runbook for packaging, compliance, and releasing `com.roamtty.app` on Google Play.

## Baseline Compliance

| Check | Requirement / Status |
| --- | --- |
| Package ID | `com.roamtty.app` (immutable after first release) |
| SDK levels | `minSdk 26`, `targetSdk 36` (bump before each August deadline) |
| 16 KB page alignment | Native libs aligned to `0x4000` |
| Security flags | `usesCleartextTraffic=false`, `allowBackup=false` |
| Data Safety | No accounts, no ads, no third-party analytics/crash SDKs ("no data collected") |
| Privacy Policy | `docs/PRIVACY.md` — live at `https://roamtty.com/privacy/` and bundled in-app |
| Foreground Service | `specialUse` (`PROPERTY_SPECIAL_USE_FGS_SUBTYPE`) — requires Play declaration + review video |
| Other permissions | `INTERNET`, `ACCESS_NETWORK_STATE`, `WAKE_LOCK`, `POST_NOTIFICATIONS` (no extra forms) |

## Signing & Artifacts

1. Bump `itermuxVersionName` in `gradle.properties` (`versionCode = MAJOR*10000 + MINOR*100 + PATCH`).
2. Build signed APK + AAB + checksums with `scripts/release.sh` (or `scripts/release.ps1`):
   - Outputs `dist/Roamtty-<version>.apk`, `dist/Roamtty-<version>.aab`, `dist/SHA256SUMS.txt`.
   - Never pass test overrides like `-PitermuxApplicationId`.
3. **Play App Signing**: Keep `.private/signing/itermux-release.p12` backed up offline (as Play upload key or imported signing key via `pepk` if GitHub APKs must cross-upgrade with Play installs).

## Play Console Declarations

- **Foreground Service (`specialUse`)**: Declare active user-initiated SSH/mosh/desktop connections kept alive in background and released when the last session disconnects. Link the unlisted demo video (rendered from `video/` `PlayReview`).
- **App Access**: Provide a disposable, low-privilege demo SSH host (address, port, user, password, and first-connect fingerprint confirmation note) so reviewers can test core features.
- **Content Rating & Audience**: Utility/Productivity, 13+/18+ (not directed at children), no ads, no financial/health/news/government claims.
- **Listing Copy**: Avoid stuffing third-party trademarks (Claude Code, Codex, Grok) into store metadata; use generic phrasing ("remote AI coding tools"). Keep screenshots free of real server addresses or keys.
- **Support & Website URLs**:
  - Privacy: `https://roamtty.com/privacy/`
  - Support: `https://roamtty.com/support/` (`/en/support/`)
  - Contact email: `roamtty@gmail.com` (keep in sync with `docs/PRIVACY.md` and `yahaa/roamtty-website`)

## Pre-Release Verification

- [ ] Run JVM tests & lint:
  `./gradlew :app:lint :app:testDebugUnitTest :ssh-core:test :desktop-core:test :code-core:test :terminal-emulator:testDebugUnitTest :terminal-view:testDebugUnitTest`
- [ ] Smoke-test universal APK generated from the release AAB (`bundletool build-apks --mode=universal`) to verify R8 rules (`app/proguard-rules.pro`) against JSch / Bouncy Castle.
- [ ] Verify release build contains no debug logs, test keys, or `test_password`.
- [ ] Spot-check SSH login, tmux reattach, mosh fallback, SFTP transfer, remote desktop, and foreground notification disconnect.
- [ ] Roll out staged (e.g. `10% → 50% → 100%`) and monitor Android Vitals (crash rate, ANR, excessive wake locks).
