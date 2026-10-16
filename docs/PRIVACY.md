# Roamtty 隐私政策

生效日期：2026-10-16

Roamtty 是一款 SSH 终端客户端，提供 Android 版与 iOS 版（以下合称“应用”）。我们（Roamtty 开发者）不运营任何应用后端服务器，不要求注册账号，不收集、不上传、不出售、也不与任何第三方共享你的个人数据。本政策适用于从 Google Play 或 App Store 获取的 Roamtty，以及网站 roamtty.com。

## 摘要

- 没有账号、广告、统计分析、崩溃上报或第三方 SDK。
- 主机配置、凭据和设置只保存在你的设备上；凭据由系统密钥库加密保护。
- 应用只连接你自己添加的服务器，开发者收不到任何数据。

## 应用在设备上保存的数据

以下数据只保存在你的设备中，仅供应用自身功能使用：

- **主机信息**：名称、地址、端口、用户名、认证方式、会话模式、检测到的远端系统类型与已安装的 tmux / mosh 状态。
- **凭据**：密码仅在你选择保存时写入；导入的私钥在保存使用私钥认证的主机时写入，没有单独的“不保存私钥”选项。私钥口令从不保存。
  - Android：密码和私钥使用 Android Keystore 中不可导出的密钥以 AES-256-GCM 加密，密文保存在本机 Room 数据库中。
  - iOS：密码和私钥保存在系统钥匙串中，访问级别为“仅限本设备、首次解锁后可用”，不会随备份或设备迁移转移到其他设备。
- **服务器信任记录**：你确认信任的服务器公钥及其算法，按服务器地址和端口保存；应用由公钥计算指纹，用于防止中间人攻击。同一地址和端口的主机配置共享此记录。
- **会话记录**：每台主机最多 8 条会话的 ID、对应主机、模式、标题、远端 tmux 会话名和会话序号，用于应用重启后恢复会话列表。不包含终端内容。
- **偏好设置**：语言、主题、字体（含你导入的自定义字体文件）、字号、操作栏和下载目录等设置。
- **你传输的文件**（iOS）：从服务器下载的文件保存在应用的“文稿”目录，可在“文件”App 的“我的 iPhone › Roamtty”中查看和删除；通过系统分享发送给应用的图片、文件和文本暂存在应用容器中，直到你上传或删除。Android 上下载的文件保存在你通过系统文件选择器指定的位置。

终端输出、输入内容和编辑草稿只存在于内存中，不写入磁盘。Android 版关闭了系统备份与设备迁移；iOS 版把主机数据库排除在 iCloud 和电脑备份之外，钥匙串中的凭据也不会进入备份。你下载到 iOS“文稿”目录的文件与其他用户文件一样，可能包含在你自己的设备备份中。

## 网络连接

- 应用只会连接到你自己添加的服务器：SSH（TCP）、mosh（UDP）以及用于显示在线状态和延迟的 TCP 连通性检测。iOS 在连接局域网内的主机前会请求“本地网络”权限。
- SSH 与 mosh 连接均加密传输；应用不发起明文 HTTP 请求。点按终端中的链接时，链接由系统浏览器或对应的应用打开。
- 连接时，应用会在远端执行只读操作：识别远端系统、检查 tmux / mosh 是否可用（结果保存在本机的主机信息中），以及读取远端 shell 历史文件作为“常用命令”。常用命令只缓存于会话的内存状态，断开后仍可能用于查看，不写入应用持久化存储，进程结束后不保留。主机详情页的监控数据（CPU、内存、磁盘、网络、进程）同样只在内存中显示。其他远端操作只在你主动发起时执行，例如安装 tmux / mosh，或通过 SFTP 上传、下载文件。
- 从应用启动 Claude Code、Codex 或 Grok 时（Windows 起始目录除外），应用会在远端写入用于显示 Agent 状态的 hook 文件：`~/.roamtty/agent-hook.sh`，以及 `~/.roamtty/claude-hooks.json`（Claude Code）或启动器 `~/.roamtty/codex`、`~/.roamtty/grok`。Codex 启动器会在 `${CODEX_HOME:-~/.codex}/config.toml` 末尾追加这些 hook 的信任记录（已有同名条目则不改动）；Grok 启动器会写入 `${GROK_HOME:-~/.grok}/hooks/roamtty.json`，其中的 hook 只对从应用启动的 Grok 生效。hook 把 Agent 的状态、正在执行的工具和命令、你最近一条提示词的前 200 个字符，以及 Agent 最近一次回复的前 400 个字符，保存为该 tmux 会话的选项，Agent 退出时清除；应用通过 SSH 读取这些选项，在会话列表显示，只保存在内存中，不写入手机存储，也不发往其他地方。这些文件留在服务器上，可随时删除 `~/.roamtty` 目录、`config.toml` 中追加的条目和 `roamtty.json`；下次从应用启动 Agent 时会重新写入。不从应用启动 Agent 时不会写入这些文件。
- 应用不包含广告、统计分析、崩溃上报或任何第三方 SDK，不会向开发者或第三方发送任何数据。

## 权限

| 平台 | 权限 | 用途 |
| --- | --- | --- |
| Android | 网络访问、网络状态 | 连接你添加的服务器，检测网络变化 |
| Android | 前台服务、唤醒锁 | 在后台保持你主动打开的终端连接；最后一个连接断开即释放 |
| Android | 通知 | 显示进行中的连接，并提供“全部断开”操作 |
| iOS | 本地网络 | 连接你添加的、位于局域网内的服务器 |

文件上传、下载和导入私钥、字体都通过系统文件选择器或系统分享完成，应用只能访问你选择的文件；照片同样只在你选择后读取，仅用于上传到你指定的服务器。应用不访问通讯录、位置、相机、麦克风或广告标识符。

## 第三方服务

应用本身不使用第三方服务。如果你从 Google Play 或 App Store 安装，Google 或 Apple 会依照其自身的隐私政策处理安装、购买、付款和商店相关数据（例如下载量、订单信息和你选择共享的崩溃报告），这不在本政策范围内。你连接的服务器由你或服务器管理员控制，其数据处理方式由对方负责。

## 网站

roamtty.com 是托管在 GitHub Pages、经 Cloudflare 分发的静态网站，不使用 Cookie、统计分析或跟踪脚本。GitHub 和 Cloudflare 作为托管与网络服务商，可能出于安全和运维目的处理访问者的 IP 地址等技术日志，适用其各自的隐私政策。

## 数据保留与删除

我们不持有你的任何数据，因此没有需要向我们申请删除的内容。你可以随时自行删除设备上的数据：

- 删除主机会删除该主机配置、已保存的凭据和会话记录；服务器信任记录独立保留，可通过“重新核验”移除。
- Android：在系统设置中清除 Roamtty 的存储空间，或卸载应用，即删除应用保存的全部本地数据。
- iOS：卸载应用会删除应用数据和文稿目录；钥匙串中的凭据可在卸载前于应用内删除主机来移除。
- 服务器上的 hook 文件按上文“网络连接”所述自行删除。

## 安全

凭据由系统密钥库保护，主机指纹变化时连接会被阻止，所有连接均经 SSH 或 mosh 加密。如发现安全问题，请通过下方邮箱私下告知我们，不要公开披露。

## 儿童

本应用面向开发者与技术用户，不面向 13 岁以下儿童，也不会有意收集儿童的任何信息。

## 政策变更

本政策如有变更，会在本页面更新并修改生效日期。

## 联系方式

如对本政策有任何疑问，请发送邮件至 support@roamtty.com，或访问 https://roamtty.com/support/ 。

# Roamtty Privacy Policy

Effective date: 2026-10-16

Roamtty is an SSH terminal client for Android and iOS (together, “the app”). We, the Roamtty developers, run no app backend servers and require no account. We do not collect, upload, sell or share any of your personal data with anyone. This policy covers Roamtty obtained from Google Play or the App Store, and the website roamtty.com.

## Summary

- No accounts, ads, analytics, crash reporting or third-party SDKs.
- Hosts, credentials and settings stay on your device; credentials are protected by the system keystore.
- The app connects only to servers you add. The developers receive no data.

## Data stored on your device

The following data stays on your device and is used only by the app itself:

- **Hosts**: name, address, port, username, authentication method, session mode, the detected remote system and whether tmux / mosh are installed.
- **Credentials**: passwords are stored only when you choose to save them. An imported private key is stored when you save a host using key authentication; there is no separate option to leave the key unsaved. Key passphrases are never saved.
  - Android: passwords and private keys are encrypted with AES-256-GCM using a non-exportable Android Keystore key, and the ciphertext is stored in the local Room database.
  - iOS: passwords and private keys are stored in the system Keychain with “this device only, after first unlock” accessibility, so they never move to another device through backups or device transfer.
- **Server trust records**: the server public keys and their algorithms you chose to trust, stored by server address and port. The app calculates fingerprints from these keys to detect man-in-the-middle attacks. Host entries using the same address and port share the record.
- **Session list**: the ID, host, mode, title, remote tmux session name and session number of up to 8 sessions per host, used to restore the session list after the app restarts. It contains no terminal content.
- **Preferences**: language, theme, font (including font files you import), font size, control bar and download folder settings.
- **Files you transfer** (iOS): files downloaded from a server are saved in the app's Documents folder, visible and removable in the Files app under “On My iPhone › Roamtty”. Images, files and text shared to the app are held in the app's container until you upload or delete them. On Android, downloads are saved where you choose with the system file picker.

Terminal output, input and text drafts are kept in memory only and never written to disk. The Android app disables system backup and device transfer. The iOS app excludes its host database from iCloud and computer backups, and Keychain credentials never enter backups. Files you download into the iOS Documents folder may be included in your own device backups, like your other files.

## Network connections

- The app connects only to servers you add: SSH (TCP), mosh (UDP), and TCP reachability checks that show whether a host is online and its latency. On iOS, the system asks for Local Network permission before the app connects to a host on your local network.
- SSH and mosh connections are encrypted; the app makes no cleartext HTTP requests. Links you tap in the terminal open in the system browser or the matching app.
- When connecting, the app performs read-only operations on the server: it identifies the system, checks whether tmux / mosh are available (stored in the host entry on your device), and reads the remote shell history file to suggest common commands. Common commands are cached only in the session's memory and may remain available to view after disconnecting. They are not written to persistent app storage and do not survive process termination. Host monitoring data (CPU, memory, disk, network, processes) is likewise only shown from memory. Everything else happens only when you ask for it, such as installing tmux / mosh or uploading and downloading files over SFTP.
- When you start Claude Code, Codex or Grok from the app (except in a Windows start folder), the app writes hook files to the server so it can show the agent's status: `~/.roamtty/agent-hook.sh`, plus `~/.roamtty/claude-hooks.json` (Claude Code) or the launchers `~/.roamtty/codex` and `~/.roamtty/grok`. The Codex launcher appends trust entries for these hooks to the end of `${CODEX_HOME:-~/.codex}/config.toml` (an existing entry with the same name is left alone); the Grok launcher writes `${GROK_HOME:-~/.grok}/hooks/roamtty.json`, whose hooks act only for a Grok started from the app. The hooks store the agent's state, the tool and command it is running, the first 200 characters of your latest prompt and the first 400 characters of the agent's latest reply as options of that tmux session, cleared when the agent exits. The app reads them over SSH to show in the session list; they are kept in memory only, never written to the phone's storage or sent anywhere else. The files stay on the server; you can delete the `~/.roamtty` folder, the entries appended to `config.toml` and `roamtty.json` at any time, and the next agent started from the app writes them again. None of these files are written unless you start an agent from the app.
- The app contains no ads, analytics, crash reporting or third-party SDKs, and sends no data to the developers or any third party.

## Permissions

| Platform | Permission | Purpose |
| --- | --- | --- |
| Android | Internet, network state | Connect to the servers you add and notice network changes |
| Android | Foreground service, wake lock | Keep the terminal connections you opened alive in the background; released when the last one disconnects |
| Android | Notifications | Show ongoing connections and offer a “Disconnect all” action |
| iOS | Local Network | Connect to servers you add on your local network |

Uploads, downloads and importing keys or fonts go through the system file picker or the system share sheet, so the app can access only the files you pick. Photos are likewise read only after you pick them, and only uploaded to the server you choose. The app does not access contacts, location, camera, microphone or the advertising identifier.

## Third-party services

The app itself uses no third-party services. If you install it from Google Play or the App Store, Google or Apple handles installation, purchase, payment and store data (such as download counts, order details and crash reports you choose to share) under its own privacy policy, which this policy does not cover. The servers you connect to are controlled by you or their administrators, who are responsible for how they handle data.

## Website

roamtty.com is a static website hosted on GitHub Pages and delivered through Cloudflare. It uses no cookies, analytics or tracking scripts. As hosting and network providers, GitHub and Cloudflare may process technical logs such as visitors' IP addresses for security and operations, under their own privacy policies.

## Data retention and deletion

We hold none of your data, so there is nothing to ask us to delete. You can delete the data on your device at any time:

- Deleting a host removes its configuration, saved credentials and session records. Server trust records remain stored separately and can be removed with Verify again.
- Android: clearing Roamtty's storage in system settings, or uninstalling the app, deletes all local data stored by the app.
- iOS: uninstalling the app deletes its data and Documents folder. To remove Keychain credentials, delete the hosts in the app before uninstalling.
- Remove the hook files on your servers as described under “Network connections”.

## Security

Credentials are protected by the system keystore, connections are blocked when a host fingerprint changes, and every connection is encrypted with SSH or mosh. If you find a security issue, please report it privately to the email below rather than disclosing it publicly.

## Children

The app is intended for developers and technical users. It is not directed at children under 13 and does not knowingly collect any information from children.

## Changes

If this policy changes, this page will be updated along with its effective date.

## Contact

If you have questions about this policy, email support@roamtty.com or visit https://roamtty.com/support/.
