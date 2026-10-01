# 架构与设计约束

## 数据与线程

```mermaid
flowchart LR
  UI[Compose 主机与会话界面] --> S[SessionService]
  UI --> DB[Room 主机与指纹]
  DB --> V[Keystore AES-GCM 凭据]
  S --> C[TerminalConnection]
  C --> SSH[SshSession / JschSession]
  SSH --> NET[Linux OpenSSH]
  NET --> TMUX[远端 tmux / AI 工具]
  SSH -->|8 KiB 输出 + 背压| EM[主线程 TerminalEmulator]
  EM --> TV[Termux TerminalView]
  TV -->|按键 / UTF-8 / bracketed paste| SSH
```

`ssh-core` 是可在 JVM 上独立测试的 Kotlin 模块。Target、Credentials、PinStore、Prompt 和 SshSession 与 Android 无关。mwiede/JSch 负责 SSH 协议与认证，不自制密码学。Bouncy Castle 为 Android 提供现代密钥算法支持，保留 JSch 的算法默认策略，不自动启用 ssh-rsa/SHA-1 等旧算法。

`JschSession.connect` 在 IO dispatcher 执行握手和读取。输出按最多 8192 字节逐段交给挂起回调，回调等待主线程完成解析后才读下一段，因此 UI 慢时不会积压无界输出。所有终端 append、resize、paste、按键生成和绘制均在主线程串行执行。历史上限 50000 行；每个终端有独立的解析器和缓冲区。

输入有界队列容量 64，单次原始写入上限 256 KiB，编辑/粘贴上限 128 KiB。断开关闭队列并清空剩余数据；每次重连创建新的 transport 和终端，不重放旧输入。PTY resize 使用合并队列，只发送最新尺寸，网络写入不阻塞 UI。SSH keepalive 间隔 15 秒，最多 3 次未响应后断开。

## 信任与凭据

主机指纹按规范化后的 `[hostname]:port` 存储，IPv6 地址统一去掉输入方括号后再规范化。首次未知指纹需用户确认；已知地址收到不同公钥立即失败，既不会降级也不会使用通用“忽略主机密钥”开关。首次确认的写入在 Room 事务中采用 insert-if-absent，防止两个并行首次连接互相覆盖。重新核验是单独的显式操作，会关闭同地址连接并清除原信任记录，再重连核验。

Keystore 持有不可导出的 AES 密钥，AES-GCM 随机 IV 和 128 位认证标签保护本地凭据。AAD 包含主机 ID 与 `password` / `key` 用途，避免密文跨主机或跨字段置换。凭据与主机同一 Room 行保存；删除主机同时移除密文。备份、设备迁移、凭据日志、终端日志、遥测都关闭。密钥口令不落盘。不可变的 JVM/Compose 字符串不能保证立即擦除，但不会持久化，字节数组在认证完成或取消时清零。

交互式认证通过 StateFlow 暴露待回答问题，答复使用 CompletableDeferred。连接取消会同时解除等待。等待用户输入上限 120 秒；网络握手/通道建立超时 20 秒。UI 不把服务器错误字符串或库堆栈写入日志。

## 生命周期

当前源码的主机搜索只在用户点击标题栏入口时展开，收起时清除筛选。左上圆形 Logo 打开左侧导航面板，面板显示主机、会话、当前连接的标题和使用指南；标题由 `TerminalConnection` 的 StateFlow 持有，并随会话引用写入私有 `SessionJournal`。终端与 Material 界面使用同一深石墨色基底；终端默认 13 sp，已有字号偏好不覆盖。`TerminalView.setTextSize` 在字号不变时不再重复重新计算终端尺寸。操作栏顶部有显式收起按钮，固定和悬浮模式均可关闭。

终端根布局仅应用 `WindowInsets.captionBar` 和输入法边距。摄像头的 `displayCutout` 不再作用于整个终端，避免隐藏状态栏后仍留下整条顶部或侧边空白；悬浮操作按钮单独避让开孔，固定侧栏仅在栏内避让顶部/底部开孔。主机列表仍使用 `safeDrawing`，保持系统栏和标题内容分开。

连接页默认全屏，右侧操作栏默认隐藏，点击右上角 ⋯ 展开、栏顶 × 收起；显式固定的偏好仍保留。竖屏使用 48 dp 单列操作栏；横屏且键盘收起、空间足够时使用 96 dp 双列操作栏，键盘展开或高度不足时改为可滚动单列。仅显式固定时为操作栏预留宽度；编辑框打开时暂时隐藏操作栏，关闭后恢复。操作栏的“⌘”菜单提供 Esc、Tab、方向、删除和作用于下一次键盘字符的 Ctrl，“⇅”菜单提供文件传输。默认隐藏输入法，轻触终端获取焦点并打开输入法。键盘按钮在点击时读取原生窗口的 IME 可见状态，通过 `SoftwareKeyboardControllerCompat` 控制原生终端输入；隐藏前先清除 Compose 和终端焦点。界面使用 `WindowInsets.isImeVisible` 跟随键盘状态。从更多菜单打开“编辑文本”，点击编辑框输入；编辑框中的文字先进入草稿，只有“发送文本”才写入 SSH，回车独立发送。

用户主动打开连接时启动 `specialUse` 前台服务，并立即提供通知。Activity 只绑定服务和订阅 StateFlow，旋转和切换会话不负责维护底层 socket。关闭最后一个活动连接后停止前台状态并调用 stopSelf；若 Activity 仍绑定服务，可继续查看已断开的终端内容。服务使用 START_NOT_STICKY，不在系统重启后静默重连。

`SessionJournal` 只保存最多 8 条会话的 ID、Room 主机 ID、模式与标题。服务重新创建时读取对应主机并创建无凭据、无连接任务的已断开条目；已删除主机的记录不恢复。新建连接等待恢复完成，避免覆盖已有列表。断开保留记录，主动关闭移除记录，终端输出、输入队列和编辑草稿不会写盘。

活动连接持有 `PARTIAL_WAKE_LOCK`，所有连接非活动或服务销毁时释放。锁的寿命与用户打开的连接一致，没有会导致长连接再次休眠的固定到期时间。前台服务与唤醒锁仍受深度 Doze 限制；设置页提供系统电池优化管理入口，由用户选择豁免，不申请自动豁免权限。[Android 唤醒锁](https://developer.android.com/develop/background-work/background-tasks/awake/wakelock)、[Doze 限制](https://developer.android.com/training/monitoring-device-state/doze-standby)。

`FileTransferController` 由 `TerminalConnection` 持有，使用服务作用域与 StateFlow 管理任务/进度，Activity 只展示状态。SAF 下载失败或取消在 `NonCancellable + IO` 中删除文档；文档 URI 使用 `DocumentsContract.deleteDocument`，删除失败显式反馈。SFTP 操作统一转义字面路径中的 `\`、`*`、`?`（JSch `realpath` 例外，接受原始路径）；上传使用同目录固定长度随机临时文件名，避免合法目标文件名因追加后缀超长。


tmux 模式先运行只读的 `command -v tmux` 检查，再在带 PTY 的 exec channel 执行经校验的独立会话名命令 `exec tmux new-session -A -s 'itermux-<id>'`。普通 SSH 使用 shell channel。用户进入“历史”后，同一 SSH 连接的短时 exec channel 对对应远端会话运行 `copy-mode`、`display-message` 和 `send-keys -X`；命令在 Mutex 下串行，长距离定位先用 `history-top` / `history-bottom` 或翻页，避免逐行重绘。客户端只保存当前窗格的历史行数、滚动位置和浏览状态，并用它驱动右侧滑块；“实时”执行 `cancel` 后退出复制模式。文件传输使用相同已认证 SSH 连接上的独立 SFTP channel，Android SAF 提供手机侧输入输出流；上传先写远端临时文件，确认目标不存在后改名。没有远端安装、端口转发、文件同步或后台自动命令重放。

`ssh-core` 的 `TmuxHistoryController` 负责远端命令和状态解析，`app.session.TmuxHistoryCoordinator` 负责当前连接的轮询、请求失效与乐观滚动状态，`terminal-view.RemoteHistoryState` 只管理滑块位置和回调。断连会使等待中的历史请求失效；拖动期间收到的远端位置在松手时同步。协调器在同一个 Mutex 内检查请求代次并执行远端命令，退出等待当前命令结束；过期滚动不会重新进入 copy-mode。

全屏程序统一使用 `OutputHistory` 的内存文字记录，不识别品牌或注入历史快捷键。emulator 在清屏/滚动前及每批输出解析后保存屏幕变化；去除相邻画面中重复的行，保留被覆盖文字，限制为 50,000 行和 2 Mi 个字符。采集直接读取 `TerminalRow`，保留物理行、列数、空格、折行标志及每列样式；不经过用于复制的 `getSelectedText` 合并行接口。远端 OSC 调色板覆盖冻结为真彩色，标准 ANSI 调色板仍随主题切换。`OutputHistoryView` 通过无传输能力的只读 `OutputHistorySession` 浏览固定快照，复用 `TerminalView` / `TerminalRenderer` 的字格、行距、样式和选区；只在独立 emulator 中装载可见行，远端继续接收输出，阅读位置不会跳动。历史宽度保持采集时的网格，宽记录通过 `HorizontalScrollView` 横向浏览；不将远端按列定位的界面当作段落折行。右侧 24 dp 触摸区的细滚动条固定在可见边缘，拖动只更新本地快照视口，短记录隐藏滑块；只读视口不提供输入连接和粘贴动作。滚到记录末尾仍保持浏览状态，仅原操作栏的“历史/实时”按钮及系统返回键切换模式，历史页和更多菜单不另设重复入口。该记录仅包含本次连接已显示的文字，不是远端程序持久化的完整对话，也不写入磁盘。

## 上游适配

输入法长按兼容：`ImeBackspaceTracker` 按接收时间识别间隔不超过 250 ms、持续 1000 ms 的退格流，清空后抑制同次长按的剩余退格。新的文字输入、方向键、重新建立输入连接或退格间隔中断会重置。入口覆盖 `sendKeyEvent`、`deleteSurroundingText` 和码点版本，操作栏和硬件键盘不参与推断。微信输入法不提供有效 repeatCount/downTime，快速连续点按可能触发相同规则。

`InputConnection` 使用实际光标所在逻辑行（向前包含自动折行，最多八个屏幕行）或正在组合的文本响应 extracted/surrounding text 查询，保留 UTF-16 选区与空格，不返回整个历史，也不伪造占位文字。提供该上下文后微信输入法才能持续发送长按退格。[Android InputConnection 接口](https://developer.android.com/reference/android/view/inputmethod/InputConnection)。

输入控件：草稿以 `TextFieldValue` 保存选区，ICU `BreakIterator` 按字素边界移动和删除，内容仍只存于 `TerminalConnection.draft` 内存。终端方向键与退格经 `KeyHandler` 生成，与远端普通/应用光标模式一致；长按清空发送 Ctrl+U、Ctrl+K，不调用 emulator reset，也不清除滚动历史。

`TranscriptScrollBar` 在 `TerminalView` 内绘制历史位置，6 dp 宽、24 dp 触摸区域。普通 SSH 拖动映射本地 topRow；应用 tmux 模式进入历史后映射远端 `history_size` / `scroll_position`，手势与拖动都经独立 exec channel 控制 tmux。闲置 1200 ms 后用 250 ms 淡出；阅读旧内容时滑块保留低对比度提示，仅滑块附近捕获触摸，避免透明轨道误抢边缘手势。悬浮操作栏距右边 24 dp 避开拖动区域。本地历史阅读时按 parser 的 scroll counter 平移 topRow，保留原内容位置；继续键入恢复到底部。其他无本地历史的全屏程序仍按其自身滚动方式处理。

固定 Termux commit：`4584488513c099f2e98bcfcd00f006d213248729`。只复制明确声明 Apache-2.0 例外的 terminal-emulator 和 terminal-view。TerminalSession 被改为接口，删除了 JNI/local-process 实现，不需要安装 Termux。IME 配置改成真正的文本输入类型；组合阶段只改本地 Editable，commit 才写入终端；组合输入期间的删除不回传远端。远端 OSC 52 不可写 Android 剪贴板，用户选择复制仍可用。粘贴的 bracketed 起止序列和内容以一次写入入队。

应用窗口默认禁止系统截图与最近任务快照。测试仅在测试 Activity 中临时关闭该标志，截图内容均来自专用测试环境。
