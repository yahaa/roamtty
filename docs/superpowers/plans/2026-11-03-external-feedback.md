# External feedback repair and closed-test update

Goal: resolve PrimeTestLab report 9904 against origin/main f9439af6, validate, and submit a signed update to the existing Google Play closed test.

Requirements (approved by the user's request to fix all feedback and submit):
- M-01: first launch follows device language (Chinese for zh, English otherwise); existing saved explicit language wins.
- M-02: reject malformed host addresses including server..example without DNS/reachability validation; retain valid short hostnames, DNS, IPv4, IPv6 and scoped IPv6.
- M-03: distinguish never-probed desktop from confirmed no desktop in host cards; preserve confirmed desktop behavior.
- M-04: friendly connection failure text with raw detail collapsed behind Details.
- S-01: password show/hide in connection credentials prompt, concealed by default, no persistence.
- S-02: explicit Close/back action alongside reconnect, preserving remote sessions.
- S-03: offline sessions visibly marked, retained for recovery with existing manual removal; verify latest main already supports this before adding code.
- Preserve Chinese and English localization, existing data and remote session semantics.

Plan:
- [ ] Reproduce language/address failures with focused tests, implement and run them green.
- [ ] Fix desktop placeholder and connection UI; add focused Compose regression coverage.
- [ ] Verify offline session marking against latest main and existing tests.
- [ ] Run unit suite, lint, focused emulator tests; inspect rendered UI. No physical device connected at start.
- [ ] Independent branch review, resolve findings, commit/push and create PR (no automatic merge).
- [ ] Inspect Play latest versionCode, build signed APK/AAB with existing upload key, verify package/version/signature and install release smoke.
- [ ] Upload existing closed test release, submit review, verify pending review state; record artifact hash and exact commit.
