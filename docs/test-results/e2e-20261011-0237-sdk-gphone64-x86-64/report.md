# Roamtty e2e report — FAIL

Device: sdk_gphone64_x86_64 · Android API 36 · emulator-5554 · commit 43ad686 · 2026-10-11 02:37

Profile `emulator` · 29/29 tests passed · 33/35 budgets met

## Tests

| Result | Test | Detail |
|---|---|---|
| ✅ passed | E2eFeatureTest.f01_addHostThroughEditorTrustsFingerprintAndDetectsSystem |  |
| ✅ passed | E2eFeatureTest.f02_searchAndEditHost |  |
| ✅ passed | E2eFeatureTest.f03_plainSshTerminalRunsCommands |  |
| ✅ passed | E2eFeatureTest.f04_newSessionFromSessionsTab |  |
| ✅ passed | E2eFeatureTest.f05_dockFontEditorAndCommonCommands |  |
| ✅ passed | E2eFeatureTest.f06_petShortcutsAndGestures |  |
| ✅ passed | E2eFeatureTest.f07_tmuxSessionSurvivesDisconnectAndRestarts |  |
| ✅ passed | E2eFeatureTest.f08_sessionsListDrawerSwitchAndClose |  |
| ✅ passed | E2eFeatureTest.f09_keyAndInteractiveAuthentication |  |
| ✅ passed | E2eFeatureTest.f10_wrongPasswordFailsAuthentication |  |
| ✅ passed | E2eFeatureTest.f11_changedHostKeyIsBlockedUntilReverified |  |
| ✅ passed | E2eFeatureTest.f12_fileBrowserAndPreviews |  |
| ✅ passed | E2eFeatureTest.f13_fileOperations |  |
| ✅ passed | E2eFeatureTest.f14_uploadShareImageAndDownload |  |
| ✅ passed | E2eFeatureTest.f15_hostDetailMonitorAndPlugins |  |
| ✅ passed | E2eFeatureTest.f16_agentLaunch |  |
| ✅ passed | E2eFeatureTest.f17_settingsLanguageThemeFont |  |
| ✅ passed | E2eFeatureTest.f18_guidePrivacyAndLicenses |  |
| ✅ passed | E2eFeatureTest.f19_rotationAndRecreationKeepTheSession |  |
| ✅ passed | E2eFeatureTest.f20_sessionLimitPerHost |  |
| ✅ passed | E2eFeatureTest.f21_deleteHostClosesItsSessions |  |
| ✅ passed | E2ePerfTest.p01_sshConnect |  |
| ✅ passed | E2ePerfTest.p02_keystrokeEchoLatency |  |
| ✅ passed | E2ePerfTest.p03_outputFlood |  |
| ✅ passed | E2ePerfTest.p05_idleScreens |  |
| ✅ passed | E2ePerfTest.p06_memoryAndLeaks |  |
| ✅ passed | E2ePerfTest.p07_sftpThroughput |  |
| ✅ passed | E2eFramesTest.r01_scrollbackScrolling |  |
| ✅ passed | E2eFramesTest.r02_hostListScrolling |  |

## Performance

| Result | Metric | Value | Budget | What it measures |
|---|---|---|---|---|
| ✅ ok | `unit_tests_failed` | 0 count | ≤ 0 | JVM unit tests of app, ssh-core, terminal-emulator, terminal-view |
| ✅ ok | `cold_start_p50_ms` | 885 ms | ≤ 1500 | am start -W TotalTime, median of 6 cold starts |
| ✅ ok | `cold_start_max_ms` | 917 ms | ≤ 2500 | slowest of the 6 cold starts |
| ✅ ok | `host_save_with_detection_ms` | 1082 ms | ≤ 5000 | 保存 → fingerprint trust → system + plugin detection done |
| ✅ ok | `host_probe_latency_ms` | 1 ms | ≤ 100 | card's DNS + TCP latency to the loopback fixture |
| ✅ ok | `ui_terminal_open_ms` | 332 ms | ≤ 3000 | 打开 tap → terminal connected |
| ✅ ok | `tmux_reattach_ms` | 701 ms | ≤ 3000 | 重新连接 tap → tmux session reattached |
| ✅ ok | `files_open_ms` | 680 ms | ≤ 3000 | 查看文件 → home listing shown |
| ✅ ok | `files_list_1500_ms` | 212 ms | ≤ 2000 | open a 1,500-file directory |
| ✅ ok | `preview_large_log_ms` | 1065 ms | ≤ 3000 | preview of a 60,000-line log |
| ✅ ok | `monitor_first_sample_ms` | 844 ms | ≤ 5000 | host page → first CPU/memory sample |
| ✅ ok | `plugin_detect_ms` | 437 ms | ≤ 5000 | 安装插件 sheet → tmux/mosh detected |
| ✅ ok | `ssh_connect_p50_ms` | 155 ms | ≤ 1500 | SSH login to shell, median of 5 |
| ✅ ok | `ssh_connect_max_ms` | 407 ms | ≤ 3000 | slowest of the 5 logins |
| ✅ ok | `echo_p50_ms` | 27 ms | ≤ 60 | key commit → echoed on screen, median of 40 |
| ✅ ok | `echo_p95_ms` | 33 ms | ≤ 120 | key echo, 95th percentile |
| ✅ ok | `flood_seq200k_ms` | 993 ms | ≤ 8000 | seq 1 200000 fully rendered |
| ✅ ok | `flood_wide_ms` | 246 ms | ≤ 4000 | 3,000 lines of 200 columns |
| ✅ ok | `flood_color_ms` | 486 ms | ≤ 6000 | 20,000 SGR-coloured lines |
| ✅ ok | `flood_seq200k_jank_pct` | 13.7 % | ≤ 70 | janky frames while flooding |
| ✅ ok | `scroll_jank_pct` | 0 % | ≤ 80 | janky frames dragging through scrollback |
| ❌ over budget | `scroll_frame_p90_ms` | 4950 ms | ≤ 40 | 90th percentile frame time in scrollback |
| ❌ over budget | `scroll_frames_drawn` | 0 frames | ≥ 30 | frames drawn while dragging through scrollback (0 = the drag never redraws) |
| ✅ ok | `hostlist_jank_pct` | 21.7 % | ≤ 60 | janky frames flinging 60 host cards |
| ✅ ok | `idle_home_cpu_ms_per_10s` | 0 ms | ≤ 200 | process CPU over 10 s on an idle home page |
| ✅ ok | `idle_home_frames_per_10s` | 0 frames | ≤ 30 | frames drawn by an idle home page |
| ✅ ok | `idle_terminal_cpu_ms_per_10s` | 20 ms | ≤ 300 | process CPU over 10 s on an idle terminal |
| ✅ ok | `idle_terminal_frames_per_10s` | 0 frames | ≤ 30 | frames drawn by an idle terminal |
| ✅ ok | `pss_home_mb` | 104.9 MB | ≤ 150 | PSS on the home page |
| ✅ ok | `pss_8_sessions_mb` | 171.1 MB | ≤ 300 | PSS with 8 sessions × 20,000 lines |
| ✅ ok | `pss_after_close_mb` | 99.2 MB | ≤ 200 | PSS after closing them |
| ✅ ok | `leaked_sessions` | 0 count | ≤ 0 | closed sessions still reachable after GC |
| ✅ ok | `sftp_download_mbps` | 76.2 MiB/s | ≥ 5 | 16 MiB SFTP download over adb reverse |
| ✅ ok | `sftp_upload_mbps` | 23.6 MiB/s | ≥ 2 | 16 MiB SFTP upload over adb reverse |
| ✅ ok | `hostlist_frame_p90_ms` | 29 ms | ≤ 40 | 90th percentile frame time flinging the host list |

Recorded without a budget: `flood_color_jank_pct` = 26.1 %, `flood_wide_jank_pct` = 16.7 %, `java_heap_8_sessions_mb` = 82.5 MB
