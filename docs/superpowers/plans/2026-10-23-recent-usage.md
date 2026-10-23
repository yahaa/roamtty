# Recent Usage Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this single task; track completion below.

**Goal:** Recently used sessions lead within their current groups; recently used hosts lead their list.

**Architecture:** Persist ordered IDs in SharedPreferences, expose observable orders, and record visible navigation centrally. Keep the underlying service session order and database host order intact.

**Tech Stack:** Kotlin, Compose, StateFlow, SharedPreferences, Android instrumentation tests.

**Spec:** `docs/superpowers/specs/2026-10-23-recent-usage-design.md`

## Global Constraints

- Base: PR #218 `7250072`; preserve existing work in the primary checkout.
- Keep existing groups and terminal tab order; no background recency updates.
- No credentials, terminal contents or timestamps stored; IDs only.

## Review Focus

- Older/unseen entries retain stable order.
- Reconnection replaces a transport without promoting its entry.
- Restoring the service must not erase the stored order.
- Deleted IDs disappear without removing newly opened entries.
- All visible entry paths, including terminal switching and desktop notifications, update usage.

## Task 1: Persist and display recent usage

**Files:** Add `app/src/main/kotlin/io/itermux/app/data/RecentUsage.kt`; modify `ItermuxApp.kt`, `ui/ItermuxScreen.kt`, `ui/HomeSessions.kt`; add `RecentUsageUiTest.kt`, `RecentUsageStorageTest.kt` and sorting unit tests.

**Interfaces:** `RecentUsage.hostIds/sessionIds: StateFlow<List<String>>`; `useHost(id)`, `useSession(id, hostId)`, `retain(hostIds, sessionIds)`; `List<T>.recentFirst(ids, id)` returns a stable ordered copy.

- [x] Write navigation tests: reopen an older session/host, return home, assert it leads; recreate the activity and verify; reconnect another session and verify it does not lead.
- [x] Run the host-order test against the baseline and confirm its ordering assertion fails.
- [x] Implement the recent-ID store, stable ordering and centralized navigation tracking.
- [x] Cover persistence, repeated opens, deletion, old entries, cold service restoration and group priority; verify unit and device tests.
- [x] Run relevant existing UI regression tests and JVM suite; build and verify an APK.
- [x] Complete independent review and verify the scroll-restoration fix; prepare the change for a PR targeting #218's branch.

Validation and bounded follow-up fixes are recorded in `docs/test-results/recent-used-lists-20261023/report.md`.
