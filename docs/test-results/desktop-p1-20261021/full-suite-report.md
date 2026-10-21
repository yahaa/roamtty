# Roamtty e2e report — FAIL

Device: sdk_gphone64_x86_64 · Android API 36 · emulator-5554 · commit 1ba1d01 · 2026-10-21 11:09

Profile `emulator` · 33/34 tests passed · 39/39 budgets met

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
| ✅ passed | E2eFeatureTest.f22_continueSessionAttachesARunningTmuxSession |  |
| ✅ passed | E2eFeatureTest.f23_forceDeleteEndsTheRemoteTmuxSession |  |
| ❌ failed | E2eDesktopTest.d01_desktopEndToEnd | java.lang.AssertionError: 'desktop-rail-operations' did not appear within 10000 ms |
| ✅ passed | E2eDesktopTest.d02_buttonHiddenWhileDisabled |  |
| ✅ passed | E2ePerfTest.p01_sshConnect |  |
| ✅ passed | E2ePerfTest.p02_keystrokeEchoLatency |  |
| ✅ passed | E2ePerfTest.p03_outputFlood |  |
| ✅ passed | E2ePerfTest.p05_idleScreens |  |
| ✅ passed | E2ePerfTest.p06_memoryAndLeaks |  |
| ✅ passed | E2ePerfTest.p07_sftpThroughput |  |
| ✅ passed | E2eFramesTest.r01_scrollbackScrolling |  |
| ✅ passed | E2eFramesTest.r02_hostListScrolling |  |
| ✅ passed | E2eFramesTest.r03_outputFloodMainThread |  |

## Performance

| Result | Metric | Value | Budget | What it measures |
|---|---|---|---|---|
| ✅ ok | `unit_tests_failed` | 0 count | ≤ 0 | JVM unit tests of app, ssh-core, terminal-emulator, terminal-view |
| ✅ ok | `cold_start_p50_ms` | 777 ms | ≤ 1500 | am start -W TotalTime, median of 6 cold starts |
| ✅ ok | `cold_start_max_ms` | 792 ms | ≤ 2500 | slowest of the 6 cold starts |
| ✅ ok | `host_save_with_detection_ms` | 1282 ms | ≤ 5000 | 保存 → fingerprint trust → system + plugin detection done |
| ✅ ok | `host_probe_latency_ms` | 0.5 ms | ≤ 100 | card's DNS + TCP latency to the loopback fixture |
| ✅ ok | `ui_terminal_open_ms` | 295 ms | ≤ 3000 | 打开 tap → terminal connected |
| ✅ ok | `tmux_reattach_ms` | 363 ms | ≤ 3000 | 重新连接 tap → tmux session reattached |
| ✅ ok | `files_open_ms` | 613 ms | ≤ 3000 | 查看文件 → home listing shown |
| ✅ ok | `files_list_1500_ms` | 205 ms | ≤ 2000 | open a 1,500-file directory |
| ✅ ok | `preview_large_log_ms` | 1084 ms | ≤ 3000 | preview of a 60,000-line log |
| ✅ ok | `monitor_first_sample_ms` | 983 ms | ≤ 5000 | host page → first CPU/memory sample |
| ✅ ok | `plugin_detect_ms` | 467 ms | ≤ 5000 | 安装插件 sheet → tmux/mosh detected |
| ✅ ok | `ssh_connect_p50_ms` | 167 ms | ≤ 1500 | SSH login to shell, median of 5 |
| ✅ ok | `ssh_connect_max_ms` | 179 ms | ≤ 3000 | slowest of the 5 logins |
| ✅ ok | `echo_p50_ms` | 22 ms | ≤ 60 | key commit → echoed on screen, median of 40 |
| ✅ ok | `echo_p95_ms` | 28 ms | ≤ 120 | key echo, 95th percentile |
| ✅ ok | `flood_seq200k_ms` | 1227 ms | ≤ 8000 | seq 1 200000 fully rendered |
| ✅ ok | `flood_wide_ms` | 309 ms | ≤ 4000 | 3,000 lines of 200 columns |
| ✅ ok | `flood_color_ms` | 467 ms | ≤ 6000 | 20,000 SGR-coloured lines |
| ✅ ok | `flood_seq200k_jank_pct` | 29.6 % | ≤ 70 | janky frames while flooding |
| ✅ ok | `scroll_jank_pct` | 87.0 % | ≤ 95 | janky frames dragging through scrollback |
| ✅ ok | `scroll_frame_p90_ms` | 32 ms | ≤ 40 | 90th percentile frame time in scrollback |
| ✅ ok | `scroll_frames_drawn` | 144 frames | ≥ 30 | frames drawn while dragging through scrollback (0 = the drag never redraws) |
| ✅ ok | `flood_seq200k_main_cpu_ms` | 340 ms | ≤ 1500 | main-thread CPU parsing and drawing seq 1 200000 at the real frame rate |
| ✅ ok | `flood_seq200k_frame_p90_ms` | 38 ms | ≤ 60 | 90th percentile frame time while seq 1 200000 is drawn |
| ✅ ok | `flood_color_main_cpu_ms` | 250 ms | ≤ 800 | main-thread CPU parsing and drawing 20,000 SGR-coloured lines at the real frame rate |
| ✅ ok | `flood_color_frame_p90_ms` | 40 ms | ≤ 60 | 90th percentile frame time while the coloured lines are drawn |
| ✅ ok | `hostlist_jank_pct` | 20.3 % | ≤ 60 | janky frames flinging 60 host cards |
| ✅ ok | `idle_home_cpu_ms_per_10s` | 0 ms | ≤ 200 | process CPU over 10 s on an idle home page |
| ✅ ok | `idle_home_frames_per_10s` | 0 frames | ≤ 30 | frames drawn by an idle home page |
| ✅ ok | `idle_terminal_cpu_ms_per_10s` | 0 ms | ≤ 300 | process CPU over 10 s on an idle terminal |
| ✅ ok | `idle_terminal_frames_per_10s` | 0 frames | ≤ 30 | frames drawn by an idle terminal |
| ✅ ok | `pss_home_mb` | 104.3 MB | ≤ 150 | PSS on the home page |
| ✅ ok | `pss_8_sessions_mb` | 176.8 MB | ≤ 300 | PSS with 8 sessions × 20,000 lines |
| ✅ ok | `pss_after_close_mb` | 105.2 MB | ≤ 200 | PSS after closing them |
| ✅ ok | `leaked_sessions` | 0 count | ≤ 0 | closed sessions still reachable after GC |
| ✅ ok | `sftp_download_mbps` | 62.2 MiB/s | ≥ 5 | 16 MiB SFTP download over adb reverse |
| ✅ ok | `sftp_upload_mbps` | 15.8 MiB/s | ≥ 2 | 16 MiB SFTP upload over adb reverse |
| ✅ ok | `hostlist_frame_p90_ms` | 28 ms | ≤ 40 | 90th percentile frame time flinging the host list |

Recorded without a budget: `desktop_install_first_frame_ms` = 2070 ms, `desktop_resume_first_frame_ms` = 194 ms, `desktop_rtt_ms` = 4 ms, `desktop_static_frames_3s` = 0 count, `flood_color_jank_pct` = 28.6 %, `flood_wide_jank_pct` = 26.7 %, `java_heap_8_sessions_mb` = 83.8 MB
