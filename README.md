# Roamtty

面向远程 AI 编程的免费开源 Android SSH 客户端（原名 iTermux）。Android 8.0（API 26）起，Kotlin + Compose，默认简体中文、可切换 English。应用标识 `com.roamtty.app`，Apache-2.0。

## 功能

- **主机**：密码、交互式认证或 OpenSSH/PEM 私钥（Ed25519、RSA，含加密私钥）；保存时自动检测远端系统并显示图标，主机卡片显示在线状态与 TCP 建连延时（不含 DNS 解析、SSH 欢迎消息及登录耗时）。密码仅在勾选后用 Keystore 加密保存，私钥口令不落盘。
- **信任**：首次连接需核对 SHA-256 指纹；指纹变化直接阻止连接，只能显式重新核验。
- **会话**：tmux 模式为每个会话分配独立远端会话名，断线后可重新附着；主机装有 mosh 时终端优先走 UDP（需放行 60000–61000），8 秒无回应自动回退 SSH。每台主机最多 8 个会话，可自定义标题。主机菜单可一键检测并安装 tmux / mosh。
- **终端**：单击输出中的 HTTP(S) 链接打开，GitHub 地址优先使用可用的 GitHub App，其余情况使用手机浏览器（支持自动折行、回滚历史，以及终端界面自行分行显示的括号内下划线链接）；双击发送 Tab、三击粘贴、长按选择复制或拖动光标；悬浮操作栏提供 Esc / Tab / Ctrl / Shift / 方向键、编辑文本、常用命令（远端 shell 历史）、字号与字体（内置多款等宽字体，附 Nerd Font 图标回退）。
- **历史**：普通 SSH 本地回滚；tmux 模式浏览远端 copy-mode 历史；Claude Code、Codex 等全屏程序提供本次连接的输出记录（内存中最多 50,000 行 / 2 Mi 字符）。
- **文件**：SFTP 浏览、预览（文本、代码高亮、Markdown、图片）、重命名、删除、上传、多选与模糊搜索下载、插入图片路径到终端，以及系统分享图片上传。

本应用不自带模型，也不在手机上运行 Linux；AI 工具由远端环境提供。不包含端口转发、跳板机、云同步与本地 shell。

主机延时取最近一次成功探测的 TCP 建连时间，通过 Tailscale 连接时沿实际隧道路径测量；它是建连耗时，不是持续采样的已连接会话 RTT。SSH 欢迎消息单独用于确认在线，等待时间不计入延时。已有活跃会话时暂停额外探测，因此数值可能保留至下次检测。如果使用会终止 TCP 的代理或用户态子网路由，建连时间可能只反映到代理的这一段。

Windows 原生 OpenSSH 主机的起始文件夹通过 PowerShell 读取；选择子目录会在该目录打开 Windows PowerShell，退出后回到外层登录 shell。直接打开主目录保留服务器默认 shell，目录读取失败也可打开主目录或重试。通过 `.cmd` 启动的 AI 助手需使用盘符路径；网络共享目录请先映射为盘符，避免 CMD 在错误目录运行。

Windows 主机监控使用系统自带的 PowerShell 5.1 和本地 CIM 查询，显示 CPU、内存、分页文件、固定磁盘、网络流量及占用最高的进程；安装 `nvidia-smi.exe` 时还可读取 NVIDIA GPU。可选计数器不可用时保留其余数据，采集失败会显示错误并允许重试。

## 隐私

无账号、无广告、无遥测。Room 保存主机元数据、凭据密文及服务器信任记录（算法和公钥）；Android Keystore 保存不可导出的 AES 密钥，凭据以 AES-256-GCM 加密，AAD 绑定主机 ID 与用途。密码仅在选择保存时写入，导入的私钥在保存主机时加密写入，私钥口令不落盘。终端内容、编辑草稿和常用命令只存在于内存中；断开后仍可查看该会话已加载的常用命令，进程结束后不保留。会话日志每台主机最多保留 8 条，记录会话 ID、主机 ID、模式、标题、远端 tmux 会话名和会话序号。删除主机会移除其配置、凭据和会话记录；服务器信任记录独立保留，可通过重新核验移除。系统备份已排除，远端 OSC 52 不能改写剪贴板。活动连接期间持有前台服务和唤醒锁，最后一个连接断开即释放。

## 构建

需要 JDK 17、Android SDK platform 36 与 build-tools 36.0.0（AGP 8.10.1、Kotlin 2.1.21）。JDK 下载地址与校验值见 `toolchains.lock.json`。

```sh
./gradlew :app:assembleDebug :ssh-core:test :terminal-emulator:testDebugUnitTest :app:lint
```

`-PitermuxVersionName=...` 可标记测试包版本，`-PitermuxApplicationId=...` 可用独立包名安装测试包。Release 构建开启 R8 代码与资源压缩，规则见 `app/proguard-rules.pro`。

GitHub 链接回归：`scripts/test-github-links.sh emulator-5554`，在未安装 GitHub 的隔离模拟器中构建测试处理应用，验证动态启用链接入口、停用入口、挂起应用、停用应用和未安装时的打开与回退行为，结束后卸载测试处理应用。

Windows 上可用 `scripts/bootstrap.ps1`、`scripts/install-sdk.ps1` 在项目目录内安装工具链，`scripts/build.ps1` 构建。

### 签名与发布

版本号在 `gradle.properties` 的 `itermuxVersionName` 中维护，`versionCode` 由其推导为 `MAJOR*10000 + MINOR*100 + PATCH`（0.1.0 → 100），每次上传 Google Play 前须递增版本号；`-PitermuxVersionCode=...` 可临时覆盖。

`scripts/release.sh`（Linux / macOS）或 `scripts/release.ps1`（Windows）构建签名的 `dist/Roamtty-<版本>.apk`（直装）与 `dist/Roamtty-<版本>.aab`（上传 Google Play），校验签名并写入 `SHA256SUMS.txt`。发布密钥为 3072 位 RSA（`.private/signing/itermux-release.p12`，密码在被忽略的 `signing.properties`），同时作为 Play 上传密钥。`release.ps1` 首次运行时生成密钥；`release.sh` 只在设置 `ITERMUX_CREATE_RELEASE_KEY=1` 时生成，否则要求恢复已有密钥。两者须单独备份，丢失后无法覆盖升级。调试包使用开发签名，不能覆盖发布包。

## 测试

```sh
./gradlew :app:testDebugUnitTest :ssh-core:test :terminal-emulator:testDebugUnitTest :terminal-view:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest   # 需要模拟器或设备
```

Windows 目录和监控命令测试使用 `ROAMTTY_PWSH=/path/to/pwsh ./gradlew :app:testDebugUnitTest --tests '*WindowsShellTest' --tests '*WindowsHostMetricsTest'`。页面回归可启动 `scripts/windows-launch-fixture.py`（需 Paramiko 和 `ROAMTTY_PWSH`），执行 `adb reverse tcp:22248 tcp:22248` 后，以 `windowsLaunchFixture=true` 运行 `WindowsLaunchSheetTest`、`WindowsHostMonitorTest`。该夹具运行真实 PowerShell 并模拟 Windows 路径和 CIM 数据，不替代 Windows 真机验证。

原生 Windows 监控集成测试 `WindowsHostMetricsIntegrationTest` 通过真实 SSH 运行生产采集器。使用一次性测试主机，设置 `ROAMTTY_WINDOWS_PASSWORD_FILE` 指向本地密码文件，以及 `ROAMTTY_WINDOWS_HOST`、`ROAMTTY_WINDOWS_PORT`、`ROAMTTY_WINDOWS_USER`（默认 `127.0.0.1:22249`、`vagrant`）。Android 的原生 Windows 监控用例需反向转发端口 22249、参数 `windowsNativeMonitor=true`，以及有 CIM 权限的管理员测试账号 `roamtty_admin` 和无 CIM 权限的普通测试账号 `roamtty_monitor`，密码均为 `Fixture-only-123!`；该公开密码只可用于隔离测试环境。

真实 OpenSSH 测试为可选项：`scripts/prepare-openssh-test.sh`（需 root）在回环端口 22228（正常）和 22229（tmux 检查失败）启动隔离 sshd，`scripts/cleanup-openssh-test.sh` 清理；`ssh-core` 仅在存在 `.private/test-password` 时运行这些测试。设备测试通过 `adb reverse tcp:22228 tcp:22228` 访问。`scripts/ssh-key-echo.py` 提供按键回显与 SFTP 夹具，`scripts/process-death-test.sh` 验证进程被杀后的会话恢复。测试私钥是公开材料，不能用于真实服务器。

## 文档

- [架构与设计约束](docs/ARCHITECTURE.md)
- [Termius 功能对照](docs/TERMIUS_COMPARISON.md)
- [Google Play 上架清单](docs/GOOGLE_PLAY_RELEASE.md)
- [隐私政策](docs/PRIVACY.md)
- [Logo 设计预览](docs/previews/logo-redesign.html)
- [第三方许可](THIRD_PARTY_NOTICES.md)
