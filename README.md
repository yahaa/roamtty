# Roamtty

面向远程 AI 编程的免费开源 Android SSH 客户端（原名 iTermux）。Android 8.0（API 26）起，Kotlin + Compose，默认简体中文、可切换 English。应用标识 `com.roamtty.app`，Apache-2.0。

## 功能

- **主机**：密码、交互式认证或 OpenSSH/PEM 私钥（Ed25519、RSA，含加密私钥）；保存时自动检测远端系统并显示图标，主机卡片显示在线状态与延迟。密码仅在勾选后用 Keystore 加密保存，私钥口令不落盘。
- **信任**：首次连接需核对 SHA-256 指纹；指纹变化直接阻止连接，只能显式重新核验。
- **会话**：tmux 模式为每个会话分配独立远端会话名，断线后可重新附着；主机装有 mosh 时终端优先走 UDP（需放行 60000–61000），8 秒无回应自动回退 SSH。每台主机最多 8 个会话，可自定义标题。主机菜单可一键检测并安装 tmux / mosh。
- **终端**：双击发送 Tab、三击粘贴、长按选择复制或拖动光标；悬浮操作栏提供 Esc / Tab / Ctrl / Shift / 方向键、编辑文本、常用命令（远端 shell 历史）、字号与字体（内置多款等宽字体，附 Nerd Font 图标回退）。
- **历史**：普通 SSH 本地回滚；tmux 模式浏览远端 copy-mode 历史；Claude Code、Codex 等全屏程序提供本次连接的输出记录（内存中最多 50,000 行 / 2 Mi 字符）。
- **文件**：SFTP 浏览、预览（文本、代码高亮、Markdown、图片）、重命名、删除、上传、多选与模糊搜索下载、插入图片路径到终端，以及系统分享图片上传。

本应用不自带模型，也不在手机上运行 Linux；AI 工具由远端环境提供。不包含端口转发、跳板机、云同步与本地 shell。

## 隐私

无账号、无广告、无遥测。Room 只保存主机元数据与信任记录；凭据使用 Keystore AES-256-GCM 加密，AAD 绑定主机 ID 与用途。终端内容、编辑草稿不写盘，会话日志只记录会话 ID、主机 ID、模式与标题。系统备份已排除，远端 OSC 52 不能改写剪贴板。活动连接期间持有前台服务和唤醒锁，最后一个连接断开即释放。

## 构建

需要 JDK 17、Android SDK platform 36 与 build-tools 36.0.0（AGP 8.10.1、Kotlin 2.1.21）。JDK 下载地址与校验值见 `toolchains.lock.json`。

```sh
./gradlew :app:assembleDebug :ssh-core:test :terminal-emulator:testDebugUnitTest :app:lint
```

`-PitermuxVersionName=...` 可标记测试包版本，`-PitermuxApplicationId=...` 可用独立包名安装测试包。Release 构建开启 R8 代码与资源压缩，规则见 `app/proguard-rules.pro`。

Windows 上可用 `scripts/bootstrap.ps1`、`scripts/install-sdk.ps1` 在项目目录内安装工具链，`scripts/build.ps1` 构建。

### 签名与发布

版本号在 `gradle.properties` 的 `itermuxVersionName` 中维护，`versionCode` 由其推导为 `MAJOR*10000 + MINOR*100 + PATCH`（0.1.0 → 100），每次上传 Google Play 前须递增版本号；`-PitermuxVersionCode=...` 可临时覆盖。

`scripts/release.sh`（Linux / macOS）或 `scripts/release.ps1`（Windows）构建签名的 `dist/Roamtty-<版本>.apk`（直装）与 `dist/Roamtty-<版本>.aab`（上传 Google Play），校验签名并写入 `SHA256SUMS.txt`。发布密钥为 3072 位 RSA（`.private/signing/itermux-release.p12`，密码在被忽略的 `signing.properties`），同时作为 Play 上传密钥。`release.ps1` 首次运行时生成密钥；`release.sh` 只在设置 `ITERMUX_CREATE_RELEASE_KEY=1` 时生成，否则要求恢复已有密钥。两者须单独备份，丢失后无法覆盖升级。调试包使用开发签名，不能覆盖发布包。

## 测试

```sh
./gradlew :app:testDebugUnitTest :ssh-core:test :terminal-emulator:testDebugUnitTest :terminal-view:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest   # 需要模拟器或设备
```

真实 OpenSSH 测试为可选项：`scripts/prepare-openssh-test.sh`（需 root）在回环端口 22228（正常）和 22229（tmux 检查失败）启动隔离 sshd，`scripts/cleanup-openssh-test.sh` 清理；`ssh-core` 仅在存在 `.private/test-password` 时运行这些测试。设备测试通过 `adb reverse tcp:22228 tcp:22228` 访问。`scripts/ssh-key-echo.py` 提供按键回显与 SFTP 夹具，`scripts/process-death-test.sh` 验证进程被杀后的会话恢复。测试私钥是公开材料，不能用于真实服务器。

## 文档

- [架构与设计约束](docs/ARCHITECTURE.md)
- [Termius 功能对照](docs/TERMIUS_COMPARISON.md)
- [低延时远程桌面调研与 WebRTC 接入方案](docs/REMOTE_DESKTOP_WEBRTC_PLAN.md)
- [Google Play 上架清单](docs/GOOGLE_PLAY_RELEASE.md)
- [隐私政策](docs/PRIVACY.md)
- [Logo 设计预览](docs/previews/logo-redesign.html)
- [第三方许可](THIRD_PARTY_NOTICES.md)
