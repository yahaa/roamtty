# Roamtty

面向远程 AI 编程的免费开源 Android SSH 客户端（原名 iTermux）。Android 8.0（API 26）起，Kotlin + Compose，默认简体中文、可切换 English。应用标识 `com.roamtty.app`，Apache-2.0。

## 功能

- **主机**：密码、交互式认证或 OpenSSH/PEM 私钥（Ed25519、RSA，含加密私钥）；保存时自动检测远端系统并显示图标，主机卡片显示在线状态与 DNS + TCP 建连延时（从开始 DNS 解析到 TCP 连接成功，不含 SSH 欢迎消息及登录耗时）。密码仅在勾选后用 Keystore 加密保存，私钥口令不落盘。
- **信任**：首次连接需核对 SHA-256 指纹；指纹变化直接阻止连接，只能显式重新核验。
- **会话**：tmux 模式为每个会话分配独立远端会话名，断线后可重新附着；主机装有 mosh 时终端优先走 UDP（需放行 60000–61000），8 秒无回应自动回退 SSH。每台主机最多 8 个会话。主机菜单可一键检测并安装 tmux / mosh。
- **AI Agent**：新建会话先选 Agent（Claude Code、Codex、Grok）或普通终端，再选主机和起始文件夹，可选只读（Claude Code 为计划模式）、普通或 YOLO 权限。会话卡片以 Agent 的当前任务（否则 Agent 与文件夹）为标题，显示运行中 / 等待确认 / 空闲、正在执行的命令及上一轮耗时。
- **终端**：单击输出中的 HTTP(S) 链接打开，GitHub 地址优先使用可用的 GitHub App，其余情况使用手机浏览器（支持自动折行、回滚历史，以及终端界面自行分行显示的括号内下划线链接）；双击发送 Tab、三击粘贴、长按选择复制或拖动光标，左右滑动发送方向键（滑动后按住跳到行首 / 行尾），双指缩放字号；光标行旁提供回车与 Esc 键。边缘的工具箱幽灵点按展开操作栏（上传、下载、插入图片、常用命令即远端 shell 历史、编辑文本、粘贴），按住拖向快捷键环，按前台程序（Claude Code、Codex、Grok、Gemini CLI 或 shell）提供 Esc、Ctrl、Shift、F1–F12 等按键。设置中可选内置等宽字体或自定义字体，附 Nerd Font 图标回退。
- **历史**：普通 SSH 上下滑动浏览本地回滚；应用创建的 tmux 会话开启鼠标模式，上下滑动以滚轮事件交给 tmux 滚动远端历史。
- **文件**：SFTP 浏览、预览（文本、代码高亮、Markdown、图片）、重命名、删除、上传、多选与模糊搜索下载、插入图片路径到终端，以及系统分享图片上传。

本应用不自带模型，也不在手机上运行 Linux；AI 工具由远端环境提供。不包含端口转发、跳板机、云同步与本地 shell。

主机延时取最近一次成功探测从开始 DNS 解析到 TCP 连接成功的总时间，以毫秒显示；使用 IP 地址或命中 DNS 缓存时，解析耗时通常很小。通过 Tailscale 连接时沿实际隧道路径测量；它是建连耗时，不是持续采样的已连接会话 RTT。SSH 欢迎消息单独用于确认在线，等待时间不计入延时。已有活跃会话时暂停额外探测，因此数值可能保留至下次检测。如果使用会终止 TCP 的代理或用户态子网路由，建连时间可能只反映到代理的这一段。

Windows 原生 OpenSSH 主机的起始文件夹通过 PowerShell 读取；选择子目录会在该目录打开 Windows PowerShell，退出后回到外层登录 shell。直接打开主目录保留服务器默认 shell，目录读取失败也可打开主目录或重试。通过 `.cmd` 启动的 AI Agent需使用盘符路径；网络共享目录请先映射为盘符，避免 CMD 在错误目录运行。

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

### 端到端测试

`scripts/e2e/run.sh -s <设备序列号>` 一条命令完成系统性回归：JVM 单元测试；通过真实界面逐项操作全部功能（添加主机与指纹核验、普通 SSH 与 tmux 会话、断线重连、操作栏与快捷手势、常用命令、私钥与交互式认证、错误密码与指纹变化拦截、文件浏览/预览/重命名/删除、上传、系统分享图片、下载、主机监控与插件、AI Agent启动、设置、横屏与重建、会话上限、删除主机）；再采集冷启动、SSH 建连、按键回显、输出洪泛、滚动帧、空闲 CPU、内存与泄漏、SFTP 吞吐等性能数据，按 `scripts/e2e/budgets.json` 判定（模拟器与真机分档）。报告与截图写入 `docs/test-results/e2e-<时间>-<设备>/report.md`，任一用例失败或超出预算即返回非零。

脚本先用 `scripts/e2e/fixture.sh`（需 sudo）在回环端口 22258 启动专用账号 `roamtty_e2e` 的 OpenSSH + SFTP 夹具，含 tmux、模拟 AI Agent和测试文件；mosh 依赖 UDP，`adb reverse` 无法转发，因此夹具隐藏了 mosh-server。被测包是独立的 `e2e` 构建类型（`com.roamtty.app.e2e`：release 代码、不可调试、未混淆），不会覆盖或卸载已安装的 Roamtty 及其会话，可在个人手机上运行；运行期间手机需保持解锁，约占用屏幕十分钟。`--only E2eFeatureTest#f03_plainSshTerminalRunsCommands` 可单跑用例，`--no-unit`、`--no-build` 跳过对应步骤。

Windows 目录和监控命令测试使用 `ROAMTTY_PWSH=/path/to/pwsh ./gradlew :app:testDebugUnitTest --tests '*WindowsShellTest' --tests '*WindowsHostMetricsTest'`。页面回归可启动 `scripts/windows-launch-fixture.py`（需 Paramiko 和 `ROAMTTY_PWSH`），执行 `adb reverse tcp:22248 tcp:22248` 后，以 `windowsLaunchFixture=true` 运行 `WindowsLaunchSheetTest`、`WindowsHostMonitorTest`。该夹具运行真实 PowerShell 并模拟 Windows 路径和 CIM 数据，不替代 Windows 真机验证。

原生 Windows 监控集成测试 `WindowsHostMetricsIntegrationTest` 通过真实 SSH 运行生产采集器。使用一次性测试主机，设置 `ROAMTTY_WINDOWS_PASSWORD_FILE` 指向本地密码文件，以及 `ROAMTTY_WINDOWS_HOST`、`ROAMTTY_WINDOWS_PORT`、`ROAMTTY_WINDOWS_USER`（默认 `127.0.0.1:22249`、`vagrant`）。Android 的原生 Windows 监控用例需反向转发端口 22249、参数 `windowsNativeMonitor=true`，以及有 CIM 权限的管理员测试账号 `roamtty_admin` 和无 CIM 权限的普通测试账号 `roamtty_monitor`，密码均为 `Fixture-only-123!`；该公开密码只可用于隔离测试环境。

真实 OpenSSH 测试为可选项：`scripts/prepare-openssh-test.sh`（需 root）在回环端口 22228（正常，含 SFTP）和 22229（tmux 检查失败）启动隔离 sshd，`scripts/cleanup-openssh-test.sh` 清理；`ssh-core` 仅在存在 `.private/test-password` 时运行这些测试。设备测试通过 `adb reverse tcp:22228 tcp:22228` 访问。`scripts/ssh-key-echo.py` 提供按键回显与 SFTP 夹具，`scripts/process-death-test.sh` 验证进程被杀后的会话恢复。测试私钥是公开材料，不能用于真实服务器。

设备测试分两轮跑，两个夹具都占用 22228：

- **OpenSSH 轮**：运行 `prepare-openssh-test.sh`，另起 `PYTHONPATH=.local/py-deps python3 ssh-core/src/test/resources/host-system-server.py ubuntu <事件文件> 22238` 并 `adb reverse tcp:22238 tcp:22238`（`HostSystemUiTest` 用）。然后运行 `adb shell am instrument -w -e fixturePassword "$(cat .private/test-password)" -e notClass <key-echo 类> com.roamtty.app.test/io.itermux.app.ItermuxTestRunner`。
- **key-echo 轮**：停掉 22228 上的 sshd，在主检出里运行 `PYTHONPATH=.local/py-deps python3 scripts/ssh-key-echo.py`（密码 `test-only`，SFTP 根目录为 `.local/sftp-fixture`）。然后用 `-e class` 只跑 key-echo 类，不传 `fixturePassword`。

key-echo 类：CursorEnterKeyTest、DownloadDestinationTest、DownloadFilesTest、ExploratoryQaTest、FileBrowserLifecycleTest、FilePreviewUiTest、FileTransferDeviceTest、HistoryCommandUiTest、NavigationThemeTest、SessionLifetimeTest、SessionReconnectUiTest、TerminalClipboardTest、TerminalDraftTest、TerminalExtraKeysTest、TerminalToolboxAutoHideTest、TransferLifetimeTest、UploadDirectoryUiTest。E2E、Windows、音频等测试缺少对应夹具时会自动跳过。

## 文档

- [使用指南](docs/USER_GUIDE.md)
- [架构与设计约束](docs/ARCHITECTURE.md)
- [Google Play 上架清单](docs/GOOGLE_PLAY_RELEASE.md)
- [隐私政策](docs/PRIVACY.md)（公开地址 https://roamtty.com/privacy/）
- [官网 roamtty.com 与商店网址](https://github.com/yahaa/roamtty-website)（独立私有仓库；`docs/PRIVACY.md` 合并到 main 后，在那边重新发布官网才会更新线上隐私政策）
- [Logo 设计预览](docs/previews/logo-redesign.html)
- [第三方许可](THIRD_PARTY_NOTICES.md)
