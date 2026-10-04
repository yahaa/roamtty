# Roamtty 隐私政策

生效日期：2026-10-04

Roamtty 是一款开源的 SSH 终端客户端。我们（Roamtty 开发者）不运营任何服务器，不收集、不上传、不出售、也不与任何第三方共享你的个人数据。

## 应用在设备上保存的数据

以下数据只保存在你的设备中，仅供应用自身功能使用：

- **主机信息**：名称、地址、端口、用户名、认证方式、会话模式、检测到的远端系统类型与已安装的 tmux / mosh 状态。
- **凭据**：只有在你选择保存时，密码或私钥才会使用 Android Keystore 中不可导出的密钥以 AES-256-GCM 加密后保存。私钥口令从不保存。
- **主机指纹**：你确认信任的服务器公钥指纹，用于防止中间人攻击。
- **会话记录**：最多 8 条会话的 ID、对应主机、模式与标题，用于应用重启后恢复会话列表。不包含终端内容。
- **偏好设置**：语言、字体、字号、操作栏和下载目录等设置。

终端输出、输入内容、编辑草稿和全屏程序的输出记录只存在于内存中，不写入磁盘。应用已关闭系统备份与设备迁移，上述数据不会进入 Google 云端备份。卸载应用或在应用中删除主机即可删除相应数据。

## 网络连接

- 应用只会连接到你自己添加的服务器：SSH（TCP）、mosh（UDP）以及用于显示在线状态和延迟的 TCP 连通性检测。
- SSH 与 mosh 连接均加密传输；应用禁止明文 HTTP 流量。
- 连接时，应用会在远端执行只读操作：识别远端系统、检查 tmux / mosh 是否可用（结果保存在本机的主机信息中），以及读取远端 shell 历史文件作为“常用命令”（只保存在内存中，断开即丢弃）。其他远端操作只在你主动发起时执行，例如安装 tmux / mosh，或通过 SFTP 上传、下载文件。
- 应用不包含广告、统计分析、崩溃上报或任何第三方 SDK，不会向开发者或第三方发送任何数据。

## 权限

| 权限 | 用途 |
| --- | --- |
| 网络访问、网络状态 | 连接你添加的服务器，检测网络变化 |
| 前台服务、唤醒锁 | 在后台保持你主动打开的终端连接；最后一个连接断开即释放 |
| 通知 | 显示进行中的连接，并提供“全部断开”操作 |

文件上传和下载通过 Android 系统文件选择器完成，应用只能访问你选择的文件；通过系统分享发送给应用的图片仅用于上传到你指定的服务器。

## 第三方服务

应用本身不使用第三方服务。如果你从 Google Play 安装，Google 会依照其自身的隐私政策处理安装和商店相关数据，这不在本政策范围内。你连接的服务器由你或服务器管理员控制，其数据处理方式由对方负责。

## 儿童

本应用面向开发者与技术用户，不面向 13 岁以下儿童，也不会有意收集儿童的任何信息。

## 政策变更

本政策如有变更，会在本页面更新并修改生效日期。

## 联系方式

如对本政策有任何疑问，请通过 Google Play 商店页面列出的开发者邮箱联系我们。

# Roamtty Privacy Policy

Effective date: 2026-10-04

Roamtty is an open-source SSH terminal client. We, the Roamtty developers, run no servers and do not collect, upload, sell or share any of your personal data with anyone.

## Data stored on your device

The following data stays on your device and is used only by the app itself:

- **Hosts**: name, address, port, username, authentication method, session mode, the detected remote system and whether tmux / mosh are installed.
- **Credentials**: only when you choose to save them, passwords and private keys are encrypted with AES-256-GCM using a non-exportable Android Keystore key. Key passphrases are never saved.
- **Host fingerprints**: the server key fingerprints you chose to trust, used to detect man-in-the-middle attacks.
- **Session list**: the ID, host, mode and title of up to 8 sessions, used to restore the session list after the app restarts. It contains no terminal content.
- **Preferences**: language, font, font size, control bar and download folder settings.

Terminal output, input, text drafts and full-screen program output records are kept in memory only and never written to disk. System backup and device transfer are disabled, so none of this data goes into Google cloud backups. Uninstalling the app, or deleting a host in the app, deletes the corresponding data.

## Network connections

- The app connects only to servers you add: SSH (TCP), mosh (UDP), and TCP reachability checks that show whether a host is online and its latency.
- SSH and mosh connections are encrypted; the app blocks cleartext HTTP traffic.
- When connecting, the app performs read-only operations on the server: it identifies the system, checks whether tmux / mosh are available (stored in the host entry on your device), and reads the remote shell history file to suggest common commands (kept in memory only and discarded on disconnect). Everything else happens only when you ask for it, such as installing tmux / mosh or uploading and downloading files over SFTP.
- The app contains no ads, analytics, crash reporting or third-party SDKs, and sends no data to the developers or any third party.

## Permissions

| Permission | Purpose |
| --- | --- |
| Internet, network state | Connect to the servers you add and notice network changes |
| Foreground service, wake lock | Keep the terminal connections you opened alive in the background; released when the last one disconnects |
| Notifications | Show ongoing connections and offer a “Disconnect all” action |

Uploads and downloads go through the Android system file picker, so the app can access only the files you pick. Images shared to the app from other apps are only uploaded to the server you choose.

## Third-party services

The app itself uses no third-party services. If you install it from Google Play, Google handles installation and store data under its own privacy policy, which this policy does not cover. The servers you connect to are controlled by you or their administrators, who are responsible for how they handle data.

## Children

The app is intended for developers and technical users. It is not directed at children under 13 and does not knowingly collect any information from children.

## Changes

If this policy changes, this page will be updated along with its effective date.

## Contact

If you have questions about this policy, contact us at the developer email listed on the app's Google Play page.
