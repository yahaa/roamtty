# Roamtty e2e report — PASS

Device: sdk_gphone64_x86_64 · Android API 36 · emulator-5554 · commit 731c239 · 2026-10-21 15:31

Profile `emulator` · 36/36 tests passed · 45/45 budgets met

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
| ✅ passed | E2eDesktopTest.d01_desktopEndToEnd |  |
| ✅ passed | E2eDesktopTest.d02_buttonHiddenWhileDisabled |  |
| ✅ passed | E2eDesktopTest.d03_udpWithLossRoamingAndFallback |  |
| ✅ passed | E2eDesktopTest.d04_waylandDesktop |  |
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
| ✅ ok | `unit_tests_failed` | 0 count | ≤ 0 | JVM unit tests of app, ssh-core, desktop-core, terminal-emulator, terminal-view |
| ✅ ok | `cold_start_p50_ms` | 780 ms | ≤ 1500 | am start -W TotalTime, median of 6 cold starts |
| ✅ ok | `cold_start_max_ms` | 825 ms | ≤ 2500 | slowest of the 6 cold starts |
| ✅ ok | `host_save_with_detection_ms` | 1348 ms | ≤ 5000 | 保存 → fingerprint trust → system + plugin detection done |
| ✅ ok | `host_probe_latency_ms` | 2 ms | ≤ 100 | card's DNS + TCP latency to the loopback fixture |
| ✅ ok | `ui_terminal_open_ms` | 318 ms | ≤ 3000 | 打开 tap → terminal connected |
| ✅ ok | `tmux_reattach_ms` | 349 ms | ≤ 3000 | 重新连接 tap → tmux session reattached |
| ✅ ok | `files_open_ms` | 595 ms | ≤ 3000 | 查看文件 → home listing shown |
| ✅ ok | `files_list_1500_ms` | 215 ms | ≤ 2000 | open a 1,500-file directory |
| ✅ ok | `preview_large_log_ms` | 1110 ms | ≤ 3000 | preview of a 60,000-line log |
| ✅ ok | `monitor_first_sample_ms` | 933 ms | ≤ 5000 | host page → first CPU/memory sample |
| ✅ ok | `plugin_detect_ms` | 468 ms | ≤ 5000 | 安装插件 sheet → tmux/mosh detected |
| ✅ ok | `ssh_connect_p50_ms` | 159 ms | ≤ 1500 | SSH login to shell, median of 5 |
| ✅ ok | `ssh_connect_max_ms` | 167 ms | ≤ 3000 | slowest of the 5 logins |
| ✅ ok | `echo_p50_ms` | 23 ms | ≤ 60 | key commit → echoed on screen, median of 40 |
| ✅ ok | `echo_p95_ms` | 29 ms | ≤ 120 | key echo, 95th percentile |
| ✅ ok | `flood_seq200k_ms` | 777 ms | ≤ 8000 | seq 1 200000 fully rendered |
| ✅ ok | `flood_wide_ms` | 221 ms | ≤ 4000 | 3,000 lines of 200 columns |
| ✅ ok | `flood_color_ms` | 398 ms | ≤ 6000 | 20,000 SGR-coloured lines |
| ✅ ok | `flood_seq200k_jank_pct` | 17.9 % | ≤ 70 | janky frames while flooding |
| ✅ ok | `scroll_jank_pct` | 82.6 % | ≤ 95 | janky frames dragging through scrollback |
| ✅ ok | `scroll_frame_p90_ms` | 32 ms | ≤ 40 | 90th percentile frame time in scrollback |
| ✅ ok | `scroll_frames_drawn` | 146 frames | ≥ 30 | frames drawn while dragging through scrollback (0 = the drag never redraws) |
| ✅ ok | `flood_seq200k_main_cpu_ms` | 270 ms | ≤ 1500 | main-thread CPU parsing and drawing seq 1 200000 at the real frame rate |
| ✅ ok | `flood_seq200k_frame_p90_ms` | 29 ms | ≤ 60 | 90th percentile frame time while seq 1 200000 is drawn |
| ✅ ok | `flood_color_main_cpu_ms` | 220 ms | ≤ 800 | main-thread CPU parsing and drawing 20,000 SGR-coloured lines at the real frame rate |
| ✅ ok | `flood_color_frame_p90_ms` | 44 ms | ≤ 60 | 90th percentile frame time while the coloured lines are drawn |
| ✅ ok | `hostlist_jank_pct` | 17.7 % | ≤ 60 | janky frames flinging 60 host cards |
| ✅ ok | `idle_home_cpu_ms_per_10s` | 10 ms | ≤ 200 | process CPU over 10 s on an idle home page |
| ✅ ok | `idle_home_frames_per_10s` | 0 frames | ≤ 30 | frames drawn by an idle home page |
| ✅ ok | `idle_terminal_cpu_ms_per_10s` | 30 ms | ≤ 300 | process CPU over 10 s on an idle terminal |
| ✅ ok | `idle_terminal_frames_per_10s` | 0 frames | ≤ 30 | frames drawn by an idle terminal |
| ✅ ok | `pss_home_mb` | 111.7 MB | ≤ 150 | PSS on the home page |
| ✅ ok | `pss_8_sessions_mb` | 179.1 MB | ≤ 300 | PSS with 8 sessions × 20,000 lines |
| ✅ ok | `pss_after_close_mb` | 107.2 MB | ≤ 200 | PSS after closing them |
| ✅ ok | `leaked_sessions` | 0 count | ≤ 0 | closed sessions still reachable after GC |
| ✅ ok | `sftp_download_mbps` | 76.6 MiB/s | ≥ 5 | 16 MiB SFTP download over adb reverse |
| ✅ ok | `sftp_upload_mbps` | 18.6 MiB/s | ≥ 2 | 16 MiB SFTP upload over adb reverse |
| ✅ ok | `hostlist_frame_p90_ms` | 28 ms | ≤ 40 | 90th percentile frame time flinging the host list |
| ✅ ok | `desktop_install_first_frame_ms` | 2156 ms | ≤ 15000 | desktop button tap to first frame, plugin and OpenH264 installed from nothing (plan: ≤ 15 s) |
| ✅ ok | `desktop_resume_first_frame_ms` | 673 ms | ≤ 1000 | back into a desktop kept in the background to a new frame (plan: ≤ 1 s) |
| ✅ ok | `desktop_static_frames_3s` | 0 count | ≤ 1 | frames a still desktop sends in 3 s after its refine frame (plan: none) |
| ✅ ok | `desktop_udp_roam_ms` | 156 ms | ≤ 1000 | network change on UDP (new socket) to the next frame (plan: ≤ 1 s) |
| ✅ ok | `desktop_udp_lost_frames` | 0 count | ≤ 2 | video messages lost outright over UDP with 5% loss each way |
| ✅ ok | `desktop_udp_fallback_ms` | 3519 ms | ≤ 5000 | UDP blocked to the picture running over SSH (3 s silence plus a stats tick) |

Recorded without a budget: `desktop_rtt_ms` = 3 ms, `desktop_udp_fec_recovered` = 5 count, `desktop_udp_frames_under_loss` = 23 count, `desktop_udp_loss_pct` = 0 %, `flood_color_jank_pct` = 15.8 %, `flood_wide_jank_pct` = 9.1 %, `java_heap_8_sessions_mb` = 83.8 MB
