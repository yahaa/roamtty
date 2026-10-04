# iOS 版技术方案

状态：草案，2026-10-04。目标是在 iOS 上提供与 Android 0.1 相同的功能（见 [README](../README.md) 的“功能”一节），沿用 [ARCHITECTURE.md](ARCHITECTURE.md) 中的安全与行为约束，并说明 iOS 平台无法等价实现的部分。

## 结论

- 采用**原生 Swift 重写**，不使用 Kotlin Multiplatform。现有 `ssh-core` 依赖 JSch、Bouncy Castle 和 `java.net`/`java.io`，终端来自 Termux 的 Java/Android View，在 iOS 上都不可用。真正能够共享的只是几段纯算法（输出历史对齐、tmux 状态解析、mosh 报文、shell 历史排序），代码量不足以抵消 KMP 带来的构建和调试成本。这些算法按 Kotlin 实现逐一移植，并通过**共享测试向量**保证两端行为一致。
- 功能层面除“后台常驻连接”和“禁止截图”外都能对齐。iOS 不允许前台服务或唤醒锁，因此 iOS 版把 **mosh + tmux 恢复**作为默认长任务方案，而不是可选项。

## 技术选型

| 领域 | Android 现状 | iOS 方案 | 说明 |
| --- | --- | --- | --- |
| 语言 / UI | Kotlin、Compose | Swift 6、SwiftUI，终端区域用 UIKit | 最低 iOS 17（Observation、`NWConnection` 新 API、String Catalog） |
| SSH 协议 | mwiede/JSch + Bouncy Castle | **libssh2 1.11+**（BSD）+ **OpenSSL 3**（Apache-2.0），打包为 xcframework，外层封装 Swift actor | 需要 RSA（rsa-sha2-256/512）、Ed25519、加密 OpenSSH/PEM 私钥、keyboard-interactive 和 SFTP。SwiftNIO SSH 不支持 RSA 用户密钥，也没有 SFTP，因此不作首选。Citadel（基于 NIO SSH）作为备选，在 M0 中对比 |
| mosh | 自研 `MoshClient`/`MoshProtocol`（AES-128-OCB 来自 Bouncy Castle） | 按 Kotlin 实现移植到 Swift；AES-OCB 使用已引入的 OpenSSL `EVP_aes_128_ocb`；压缩用系统 libz；UDP 用 `Network.framework` | 不引入上游 mosh C++ 代码，避免 GPL 与 App Store 分发的问题 |
| 终端模拟 / 渲染 | Termux terminal-emulator / terminal-view（固定 commit，已改造） | **SwiftTerm**（MIT），固定 commit 并 vendor 为本地 package | 需要补丁：帧采集钩子（清屏 / 滚动前回调、DEC 2026 同步输出期间不采集）、OSC 52 写剪贴板禁用、IME 组合输入行为、手势替换 |
| 本地数据库 | Room（主机、指纹） | **GRDB**（SQLite） | 显式迁移；指纹首次确认需要在事务内 insert-if-absent，GRDB 比 SwiftData 更容易保证这一点 |
| 凭据加密 | Keystore AES-256-GCM，AAD = 主机 ID + 用途 | Secure Enclave（无 SE 时用 Keychain）保管不可导出密钥，CryptoKit AES-GCM 加密，AAD 规则不变；Keychain 项使用 `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`，不参与 iCloud 同步 | 私钥口令不落盘；`Data` 用完置零（与 Android 一样只是尽力而为） |
| 会话后台 | 前台服务 `specialUse` + `PARTIAL_WAKE_LOCK` | `beginBackgroundTask`（约 30 秒）+ 回到前台时快速恢复 | 见“生命周期”一节 |
| 文件选择 | SAF | `UIDocumentPickerViewController`（导入 / 导出）、`PHPickerViewController`（相册）、App 沙盒 Documents 对“文件” App 可见 | |
| 系统分享图片 | `ShareImageIntents` | Share Extension + App Group | 见“文件”一节 |
| 字体 | assets 中的等宽字体 + Nerd Font 回退 | 复用同一批字体文件（OFL/MIT），用 `CTFontManager` 注册，通过 `kCTFontCascadeListAttribute` 回退到 Nerd Font 和 Noto Symbols 2 | |
| 多语言 | 默认简体中文，App 内切换 | String Catalog（`zh-Hans` 为开发语言，另有 `en`）；切换语言跳转到系统的“按 App 设置语言” | iOS 不建议在 App 内覆盖 Bundle 语言 |

## 工程结构

放在本仓库的 `ios/` 目录，与 Android 共用文档、字体、测试脚本和测试向量：

```
ios/
  Packages/
    RoamttyCore/        # 纯 Swift，macOS 上可 `swift test`
      SSH/              # libssh2 封装：Target、Credentials、TrustPolicy、PinStore、Prompt、SshSession
      Mosh/             # MoshProtocol、MoshClient
      Tmux/             # TmuxHistoryController、会话名校验、插件检测与安装
      Files/            # SftpOperations、RemotePaths、RemoteFiles
      History/          # OutputHistory、OutputHistoryLine（输入为抽象行模型，不依赖 SwiftTerm）
      HostSystem/
    TerminalKit/        # vendored SwiftTerm + 补丁 + 适配层（行模型、采集钩子、操作栏按键）
    Vendor/             # libssh2.xcframework、OpenSSL.xcframework（构建脚本与校验值锁定）
  App/                  # SwiftUI App：主机列表、编辑器、会话、文件、设置
  ShareExtension/
  RoamttyTests/ RoamttyUITests/
shared/test-vectors/    # Kotlin 测试导出的 JSON：mosh 报文、历史帧序列、tmux 输出、路径转义
```

`RoamttyCore` 对应 `ssh-core`，与 UI 无关，在 macOS 上独立测试。模块边界与 Android 保持一致，便于对照代码审查。

## 核心模块设计

### SSH 连接

- `SshSession` 是一个 actor，独占 libssh2 session。I/O 跑在专用串行队列上（非阻塞 socket + `poll`），对外暴露 `AsyncStream<Data>`。
- 背压沿用 Android 的规则：每段输出最多 8 KiB，等主线程解析完这一段再读下一段。输入有界队列容量 64，单次原始写入上限 256 KiB，粘贴上限 128 KiB。resize 合并为最新尺寸后再发送。
- 超时：握手和通道建立 20 秒，等待用户输入 120 秒。keepalive 每 15 秒一次，3 次无响应即断开。
- 算法策略和 Android 一样保留库的默认值，不启用 ssh-rsa/SHA-1。M0 中列出 libssh2 与 JSch 的默认算法差异，并写入本文档。
- 信任策略原样移植：端点规范化为 `[host]:port`，IPv6 去掉方括号；未知指纹必须用户确认；已知端点收到不同公钥时直接失败；“重新核验”是一个单独的显式操作。
- 系统探测（`uname` / os-release）和 tmux/mosh 插件检测、安装脚本作为字符串常量直接复用，两端保持字节一致。

### mosh

- 报文格式、分片、ACK、只保留最新服务端状态等逻辑都按 `MoshProtocol.kt` / `MoshClient.kt` 移植，并用共享测试向量（固定 key、nonce 和明文）校验加解密与 protobuf 编解码。
- 启动方式不变：通过 SSH exec 运行 `mosh-server new -s -c 256 -l LANG=…`，解析 `MOSH CONNECT` 后切换到 UDP；8 秒内没有首包就回退到 SSH PTY。
- **时钟**：静默计时使用 `clock_gettime(CLOCK_MONOTONIC_RAW)` 或 `ContinuousClock`。原因与 Android 选择 elapsedRealtime 相同：设备睡眠时不能停表，否则 mosh-server 早已超时退出，客户端却仍然显示已连接。
- 网络切换（Wi-Fi ↔ 蜂窝）：由 `NWPathMonitor` 触发，换用新的 UDP socket 继续发送。mosh 本身支持客户端漫游。

### 终端

- SwiftTerm 的 `TerminalView`（UIKit）用 `UIViewRepresentable` 嵌入 SwiftUI。所有 feed、resize、绘制都在主线程串行执行。
- **适配层 `TerminalKit`**：
  - 行模型：把 SwiftTerm 的 `BufferLine` / `CharData` 转换为 `History` 模块使用的抽象行（物理行、列数、折行标志、逐列样式）。远端 OSC 修改的调色板冻结为真彩色。
  - 采集钩子：在清屏、滚动、DEC 2026 结束时回调。SwiftTerm 没有这些钩子，需要打补丁，补丁记录在 `TerminalKit/PATCHES.md` 中。
  - OSC 52：剪贴板写入回调直接丢弃，用户手动复制不受影响。
  - 粘贴：bracketed paste 的起止序列和内容作为一次写入入队。
- **输入**：SwiftTerm 实现了 `UITextInput`。需要验证并修正中文拼音、手写和第三方键盘的 marked text：组合阶段只改本地状态，commit 后才写入终端，组合期间的删除不回传远端。Android 的 `ImeBackspaceTracker`（微信输入法长按退格）在 iOS 上没有对应问题，不移植；iOS 键盘的问题在 M0 中实测后再处理。
- **操作栏**：Esc / Tab / Ctrl / Shift / 方向键放在 `inputAccessoryView`。键盘收起时以悬浮形态显示，并保持距右边 24 pt，避开滚动条触摸区。
- **硬件键盘**（iPad 与蓝牙键盘）：用 `UIKeyCommand` / `pressesBegan` 处理 Ctrl 组合键、Esc、方向键，并支持把 Caps Lock 映射为 Esc 或 Ctrl（可选）。
- **手势**：双击发送 Tab，三击粘贴，长按进入选择或拖动光标。需要替换 SwiftTerm 默认的手势识别器，并处理好与滚动的冲突。
- 滚动条：6 pt 宽，24 pt 触摸区，闲置 1200 ms 后淡出，与 Android 一致。

### 历史

- 普通 SSH：使用 SwiftTerm 的本地回滚，阅读旧内容时按滚动计数平移视口。
- tmux 模式：`TmuxHistoryController` + `TmuxHistoryCoordinator` 移植为 actor，在同一 SSH 连接的短时 exec channel 上串行执行 `copy-mode` / `send-keys -X`，并沿用“请求代次”机制让过期请求失效。
- 全屏程序：`OutputHistory`（加权 LCS 帧对齐、上限 50,000 行 / 2 Mi 字符）作为纯 Swift 移植。用 Android 测试导出的帧序列作为共享测试向量，逐帧比较两端输出。浏览界面用一个只读的 SwiftTerm 实例装载可见行，宽记录支持横向滚动。

### 文件（SFTP）

- `SftpOperations` 移植后同样使用短生命周期的 SFTP channel，不跨重连持有旧连接。字面路径的转义规则一致；上传先写同目录的随机临时文件，确认目标不存在后再改名。
- 下载：默认写入 App 沙盒的 `Documents/Downloads`（开启 `UIFileSharingEnabled` 和 `LSSupportsOpeningDocumentsInPlace`，在“文件” App 中可见），也可以用导出选择器另存。失败或取消时删除不完整的文件。
- 预览：文本、代码高亮、Markdown、图片。`SyntaxHighlighter` / `MarkdownParser` 是纯 Kotlin 逻辑，移植为 Swift，渲染改用 `AttributedString`。
- 插入图片路径：相册图片转为 PNG，上传到 `/tmp/roamtty-img-<uuid>.png`，并把 `" <path> "` 插入当前提示词（不提交），与 `CodexImageAttach` 一致。
- **Share Extension**：扩展内存上限低，而且不能直接拉起主 App，所以首版不在扩展里建立 SSH 连接。扩展只把图片写入 App Group 的待上传目录；主 App 下次进入前台时，提示用户选择主机和会话并上传。
- 后台中断：SFTP 不能使用后台 URLSession，App 进入后台约 30 秒后传输会中断。界面需要明确提示；下次连接时清理残留的临时文件。

## 生命周期（与 Android 的主要差异）

| 场景 | Android | iOS |
| --- | --- | --- |
| 切到后台 | 前台服务 + 唤醒锁，连接持续 | 申请 `beginBackgroundTask`，约 30 秒后 App 被挂起，TCP/UDP 不再收发 |
| 挂起后回到前台 | — | SSH：先主动探测一次（不必等 45 秒 keepalive 超时），已失效就立即重连；tmux 模式自动重新附着。mosh：直接用新 socket 继续，服务端状态保留，通常无感恢复 |
| App 在后台被系统杀掉 | 进程被杀后靠 `SessionJournal` 恢复为已断开的条目 | 同样的 `SessionJournal`（最多 8 条，只保存 ID、主机、模式、标题，不保存凭据），启动后显示为已断开，一键重连；内存中的全屏输出记录会丢失 |
| 屏幕截图 / 多任务快照 | `FLAG_SECURE` 禁止 | 无法禁止截图。进入后台时给窗口加遮罩，避免多任务快照泄露终端内容；可检测截图并提示 |

因此 iOS 版的默认策略是：

1. 新建主机时默认勾选 tmux，远端装有 mosh 时优先走 mosh。主机菜单保留“一键安装 tmux / mosh”。
2. 首次切到后台时，提示“iOS 会在约 30 秒后暂停连接，建议使用 tmux 和 mosh”。
3. 不使用 VoIP、定位、音频等后台模式“保活”。这类做法违反 App Store 审核指南 2.5.4，会被拒绝上架。

## 隐私与合规

- 与 Android 一致：无账号、无广告、无遥测；终端内容和编辑草稿不写盘；会话日志只记录 ID、主机、模式和标题。
- Keychain 项不同步到 iCloud；数据库文件放在 Application Support，并设置 `isExcludedFromBackup`；数据保护级别为 `NSFileProtectionComplete`（不影响后台，因为后台本来就不运行）。
- `Info.plist` 需要 `NSLocalNetworkUsageDescription`（连接局域网主机）和 `NSPhotoLibraryUsageDescription`（相册上传）。
- 隐私清单 `PrivacyInfo.xcprivacy`：声明不收集数据，以及用到的 required-reason API（如文件时间戳、`UserDefaults`）。
- 出口合规：SSH 使用标准加密算法，预计适用大众市场豁免，提交前需确认 `ITSAppUsesNonExemptEncryption` 的填写及年度自评要求。
- 许可证：libssh2（BSD-3）、OpenSSL 3（Apache-2.0）、SwiftTerm（MIT）、GRDB（MIT）以及现有字体，全部加入 `THIRD_PARTY_NOTICES` 和 App 内的许可页。

## 测试

- `RoamttyCore`：在 macOS 上运行 `swift test`。共享测试向量由 Android 端的 Kotlin 测试生成，放在 `shared/test-vectors/`，两端 CI 都会校验。
- 真实 OpenSSH：复用 `scripts/prepare-openssh-test.sh` 的配置（22228 正常、22229 tmux 检查失败）。在 macOS runner 上启动 sshd，也可以连接 Linux 测试机。
- UI：XCUITest 覆盖主机编辑、指纹确认、会话切换、操作栏、历史、文件浏览。手势和输入法相关的用例参照 `app/src/androidTest` 中同名测试逐条移植。
- 生命周期：用 XCUITest + `XCUIDevice` 把 App 切到后台再拉回，验证 tmux 重新附着和 mosh 恢复。用 Xcode 的“模拟后台被杀”验证 `SessionJournal`。
- CI：GitHub Actions macOS runner 运行构建、单元测试和模拟器 UI 测试。TestFlight 发布由维护者在本机或 Xcode Cloud 手动触发。

## 里程碑

按一名熟悉 iOS 的开发者全职估算，约 13–16 周。

| 阶段 | 内容 | 周数 | 验收 |
| --- | --- | --- | --- |
| M0 技术验证 | libssh2 + OpenSSL xcframework 构建；RSA/Ed25519/加密私钥/keyboard-interactive 连通；OpenSSL AES-OCB 与真实 mosh-server 握手；SwiftTerm 中文输入实测；后台挂起与恢复行为测量 | 2 | 写出验证结论；确定 libssh2 或 Citadel |
| M1 连接基础 | 主机库、Keychain 凭据、指纹信任、PTY 终端、keepalive、系统探测、在线状态 | 3 | 可以连接真实 OpenSSH 并操作 shell |
| M2 长任务 | tmux 独立会话名与重新附着、mosh 及 SSH 回退、前后台恢复、SessionJournal、每主机最多 8 个会话 | 3 | 后台 10 分钟后回来，tmux / mosh 会话能恢复 |
| M3 终端体验 | 操作栏、手势、编辑草稿、常用命令、字体与 Nerd Font、本地 / tmux / 全屏历史 | 3 | 共享历史测试向量全部通过 |
| M4 文件 | SFTP 浏览、预览、上传下载、多选与模糊搜索、插入图片路径、Share Extension | 2–3 | 对应 Android 文件类用例全部通过 |
| M5 发布 | 中英文本地化、许可页、隐私清单、iPad 布局与硬件键盘、TestFlight、上架审核 | 2 | TestFlight 公测 |

## 风险

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| libssh2 算法覆盖（如 chacha20-poly1305）不及 JSch | 部分服务器协商失败 | M0 对照常见服务器的默认配置测试；不行再评估 Citadel 或 libssh |
| SwiftTerm 中文 IME 与采集钩子需要较多补丁 | 进度延迟，跟进上游困难 | vendor 并固定 commit，补丁集中在适配层，有价值的修复向上游提 PR |
| 后台约 30 秒就被挂起 | 普通 SSH 会话频繁断开 | 默认使用 tmux / mosh，前台快速重连，在界面上说明 |
| App Store 审核（如被认为不完整的远程终端、加密出口） | 上架延迟 | 提前准备审核说明和演示服务器；先走 TestFlight |
| 开发需要 macOS + Xcode | 当前 Linux 环境无法构建 iOS | 使用 macOS runner 或维护者的 Mac；History、mosh 报文等不依赖 Apple 框架的部分可以先在 Linux 上用 Swift 工具链编译测试 |

## 待决策

1. SSH 库：libssh2 + OpenSSL（推荐）还是 Citadel，由 M0 结果决定。
2. 是否首版就支持 iPad 分屏和多窗口（建议 M5 只保证 iPad 可用，多窗口放到后续）。
3. 分发方式：仅 App Store，还是同时提供 TestFlight 公开链接。
4. iOS 版放在本仓库 `ios/`（推荐，便于共享测试向量和文档）还是单独仓库。
