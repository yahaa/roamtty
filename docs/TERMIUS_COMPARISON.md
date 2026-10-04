# Termius 功能对照与首版范围

核对日期：2026-09-14。依据 Termius 官方定价页和 Android 设计文章，不以第三方旧评测判断免费/付费边界。

| 能力 | Termius 官方信息 | iTermux 0.1 |
| --- | --- | --- |
| SSH、主机管理 | 核心客户端能力 | 已实现，主机本机保存 |
| 移动终端操作、会话切换 | 移动端导航的重点 | 原创深色界面，快捷键、多会话 |
| SFTP | Starter 免费方案已列出 | 已实现：浏览、预览、上传、批量下载 |
| 端口转发 | Starter 免费方案已列出 | 暂不实现 |
| 云同步、协作与自动化相关功能 | 按官方方案划分，可能随版本变化 | 首版无账号、无云端 |
| AI 工具使用 | 远端 SSH 终端可承载工具 | 中文组合输入、多行提示词编辑、tmux |
| 长任务恢复 | 取决于远端会话工具和产品能力 | 每会话独立 tmux、可选 mosh，断线重新附着 |

因此，不把“免费 SSH/SFTP/转发”当作 iTermux 的差异。首版重点是可审计的本地源码、明确的凭据存储方式，以及适合远程 AI 编程的中文输入和长任务恢复。

界面仅参考主机入口、会话切换、快捷操作这类导航原则；没有复制 Termius 的图标、商标、布局文件或视觉素材。当前产品名 iTermux 与两者均无隶属关系。

来源：

- [Termius 官方定价](https://termius.com/pricing)：当前方案和功能范围。
- [Termius Android 设计文章](https://www.termius.com/blog/termius-for-android-a-final-milestone-in-termius-redesign)：移动导航思路。
- [JSch 上游说明](https://github.com/mwiede/jsch)：现代 RSA 签名、Ed25519 与 Bouncy Castle 支持。
- [Termux 许可声明](https://github.com/termux/termux-app/blob/4584488513c099f2e98bcfcd00f006d213248729/LICENSE.md)：终端库的 Apache-2.0 例外。
- [Android 前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types)：specialUse 声明及用途说明。
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)：本地密钥保护机制。
- [Android 测试库发布说明](https://developer.android.com/jetpack/androidx/releases/test)：Espresso 3.7.0 修复 InputManager 反射调用，用于 Android 17 测试。
