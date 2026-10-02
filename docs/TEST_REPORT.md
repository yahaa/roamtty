# iTermux 0.1.0 测试与交付记录

## 2026-10-01 双击粘贴与重连动画

基线为拉取后的 `origin/main`（`6c3892b`），工作分支为 `feat/terminal-paste-reconnect-animation`。

- 终端双击在第二次抬手后读取当前剪贴板，将文字发送到当前已连接会话，不追加回车、不覆盖草稿。保留 128 KiB 上限、bracketed paste 及多行确认；空剪贴板、断开的会话、只读历史、取消/拖动/捏合/长按均不触发粘贴。tmux 历史模式会先完成返回实时终端，再发送文字。
- 连接、指纹核验和 SSH 认证期间显示居中的旋转进度环及取消按钮，成功或失败后移除；保留失败原因、重连及重新核验入口。进度层不占用终端网格行列。[实际重连截图](screenshots/terminal-reconnect-progress.png)。
- API 36 模拟器回归 **26 个不同用例通过、0 失败**：第一组 [24 项](test-results/terminal-paste-reconnect-device.txt)，补充组 [3 项](test-results/terminal-paste-reconnect-tmux.txt)，另有 [1 项实际页面动画补验](test-results/terminal-reconnect-animation.txt)，重复用例按名称去重。覆盖真实回环 SSH 的中文/表情字节、无额外回车/重复输入、草稿保持、多行确认/取消、bracketed paste 边界、会话替换、空剪贴板与断开状态，以及只读历史、手势冲突、额外按键和既有输出历史回归。真实 OpenSSH/tmux 夹具验证从历史返回后的粘贴不会自动执行命令。动画用例比较不同时间的帧，并检查可访问性语义、居中位置与取消后的状态。
- JVM 检查 **256 项通过、0 失败、29 项跳过**（app 57、terminal-view 18、terminal-emulator 150、ssh-core 31）。跳过项为可选性能基准及缺少本轮配置的外部 SSH/SFTP/Python 夹具；设备测试中的 SSH/tmux 使用独立回环夹具。Debug、AndroidTest 编译通过；Lint **0 错误、15 条既有警告、1 提示**；`git diff --check` 通过。
- 首轮设备运行被模拟器 System UI 无响应弹窗干扰，恢复模拟器后完整重跑通过；修正了测试字节提取误把 `KEY_ECHO_READY` 当成十六进制 `EC` 的问题。Compose 测试默认取消无限动画，实际页面补验在创建动画之前关闭测试时钟自动推进，以两个时间点的图像验证旋转。失败日志保留在 `.local/paste-reconnect-validation/`，未计入通过结果。本轮没有实体手机复测。

安装包为 `.local/builds/iTermux-paste-reconnect-20261001-debug.apk`，同目录有 SHA-256。复现主要检查：

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :terminal-view:testDebugUnitTest :terminal-emulator:testDebugUnitTest :ssh-core:test :app:lintDebug
```

## 2026-10-01 主干性能与职责重构

基线为 `origin/main` 的 `c6b5470`。本轮工作分支为 `refactor/performance-cohesion-20261001`，未更改依赖版本、数据库结构、网络协议或用户操作流程。

### 发现与修改

- `OutputHistory` 在每次捕获中为所有单元格分配样式数组，重叠比较还反复使用 `takeLast/take/zip/drop` 创建临时集合。现在由 `OutputHistoryLine` 独立冻结字符及样式，单样式行仅保存一个值；帧合并使用线性次数的行比较与索引前后缀，保留原来的合并、空帧、容量、颜色和宽字符语义。
- 下载进度虽然节流显示，旧代码仍在每次回调复制全部文件；改为私有可变队列加发布时快照。重复文件名不再从编号 2 反复扫描，使用每个名称的下一候选编号；原始文件名预留、大小写冲突和扩展名规则不变。
- `JschSession` 从 642 行缩减为 384 行，文件操作委托给 `SftpOperations`，远端文件模型和路径函数移至 `RemoteFiles.kt`。保留调用入口、独立通道、IO 调度、取消和临时文件清理规则。
- 基线真实 SFTP 回归复现相对路径含 `\`、`?`、`*` 时的 `realpath` 重复转义问题；修复为传入原始路径。新增用例验证返回的路径还能列目录、读取尾部、重命名和删除，不误操作邻近文件。
- shell 历史加载合并 Bash/Zsh 的重复流程，读取函数可注入测试；时间戳正则只编译一次，去重在达到 200 条时停止。下载面板缓存筛选结果和已选路径集合，避免无关重组时重复扫描目录。

### 性能对照

同一机器、JDK 17、同一份 `HotPathBenchmarkTest`，分别在独立主干检出与重构版本执行。每项预热 3 次、测量 5 次，表格为当前测试线程分配字节数的中位数；它不代表 Android 总内存、实际网络吞吐或真机帧率。耗时原始数据也保留，但会受 JVM JIT、模拟器负载和 GC 影响，不作为固定性能断言。

| 场景 | 主干分配字节 | 重构后分配字节 | 减少 |
| --- | ---: | ---: | ---: |
| 160×100 终端，300 次局部重绘捕获 | 62,491,704 | 7,466,544 | 88.1% |
| 400 个下载项，20,000 次进度回调 | 36,153,032 | 2,407,280 | 93.3% |
| 2,000 个同名下载文件 | 116,733,920 | 928,072 | 99.2% |
| 20,000 行 shell 历史解析及排序 | 18,457,072 | 6,498,792 | 64.8% |

原始基准与测试摘要见 [refactor-20261001.json](test-results/refactor-20261001.json)。可单独复现：

```sh
ITERMUX_BENCHMARK=1 ./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest --tests '*HotPathBenchmarkTest'
```

### 验证范围

- JVM 功能测试 **284 项通过，0 失败**：app 57、ssh-core 59、terminal-emulator 150、terminal-view 18。OpenSSH 和 SFTP 使用不同夹具分批运行，按测试名称合并统计；常规运行中跳过的性能基准另行执行通过。
- 新增 11 项功能回归，其中包含 10,000 组随机重叠对照、1,500 次随机帧变化与容量对照、重复行比较次数上界、混合样式/反色、下载快照隔离、未发布进度的失败保留、100 组各 100 个名称冲突对照、历史来源优先级及取消传播。
- 真实回环 SSH：密码、keyboard-interactive、加密 Ed25519/RSA、主机指纹拒绝、超时、断网、重连、不重放输入、PTY resize、tmux 滚动及历史尾部读取均通过。SFTP 覆盖上传/下载、禁止覆盖、长文件名、特殊字符路径以及文本尾部读取。
- API 36 模拟器 **53 项通过，0 失败，1 项人工实体输入法用例跳过**。分三组运行：37 项综合回归、5 项真实 tmux/全屏程序回归，以及 11 项认证/输入法测试（该组另跳过 1 项）。覆盖历史视图逐像素一致、深浅主题/宽行/复制/惯性滚动、文件选择与默认目录、真实文档流传输、取消清理、Activity 重建、SSH 认证及 Keystore、中文输入/长按删除、tmux 历史和分享图片队列；75 秒熄屏连接与服务重建检查也通过。没有连接用户的远程主机或在真机上复测。
- 修正已有设备测试的环境假设：懒加载列表主动滚动到目标，默认隐藏文件时等待可见目录，系统选择器返回后等待窗口稳定，输入法测试通过 `ActivityScenario` 恢复原实例。修正前的失败日志保留在 `.local/refactor-validation/`，不计为通过。
- Debug 与 AndroidTest 构建通过，Lint **0 错误、15 警告、1 提示**；告警位于既有依赖/资源/界面代码。`git diff --check` 通过。未升级依赖来消除版本提示。

调试 APK 为 `.local/builds/iTermux-refactor-20261001-debug.apk`，同目录包含 SHA-256。详细原始日志和 JUnit XML 位于 `.local/refactor-validation/`。本轮性能数据仅覆盖上述热点；未做所有设备型号、弱网带宽或长期耗电的量化测试。

## 2026-10-01 历史终端网格与样式修复

- 定位：旧采集调用用于复制的 `getSelectedText`，合并自动折行并丢失每列样式；历史页再用普通 Compose `Text` 排版，额外左右留白、文本行距及字体回退宽度与原终端网格不同，导致满宽边框二次折行、竖线断开。Grok 的边框较多，因此同一问题比 Codex 更明显。
- 采集改为保留物理行、列数、空格、折行标志和每列样式。独立只读 `OutputHistorySession` 只装载可见行，复用实时画面的 `TerminalView` / `TerminalRenderer`。宽记录左右拖动，标准 ANSI 颜色随主题切换，远端 OSC 覆盖冻结为真彩色；不把终端网格重新排成段落。
- 本轮 JVM：app 46、terminal-emulator 150、terminal-view 18，合计 **214 项通过、0 失败**。新增检查覆盖样式变化、中文/emoji/组合字符、物理折行、远端调色板、带背景色空行及只读快照。
- API 36 隔离包基础回归 **9 项通过**，覆盖深浅主题下与实时绘制器逐像素一致、5,000 行拖动、140 列横向浏览和可见边缘滑块、短/空记录、横竖屏、Grok/Codex 通用历史与原入口返回、会话重建及原 tmux 历史。[回归记录](test-results/history-grid-device.txt)。全屏程序使用回环 SSH/PTY 夹具，没有调用真实模型服务或在用户手机上复测。
- 后续补强原生长按复制用例，修正只读视图的选区焦点；用 Espresso 的真实长按与浮动工具栏执行 Copy，验证剪贴板内容并恢复原剪贴板，独立复验通过。[逐像素与复制复验](test-results/history-grid-copy-device.txt)、[深色网格](screenshots/history-grid-dark.png)、[浅色网格](screenshots/history-grid-light.png)。组件宿主使用与终端一致的沉浸模式，避免边缘手势拉出模拟器通知栏抢占焦点。
- 默认包名 Debug APK、AndroidTest 构建通过，Lint **0 错误、14 警告和 1 提示**。安装包：`.local/builds/iTermux-history-grid-debug.apk`。下方保留之前界面版本的记录。

## 2026-10-01 历史界面简化

- 移除记录页的“最早 / 最新 / 返回实时画面”三个按钮及更多菜单中的重复入口；仅通过原操作栏的“历史 / 实时”按钮和系统返回键退出。进入记录页收起键盘，阅读内容沿用终端字体与主题。
- 右侧细滚动条支持拖动及无障碍定位，平时淡显，拖动时强调；拖到底部仍停留在历史快照。短记录和空记录不显示滑块。
- API 36 隔离包 `io.itermux.historyuicheck`：新增 3 项组件测试和增强的 Grok 退出/重建测试 **4 项通过**，覆盖 5,000 行拖动两端、300 行高度的单条记录、短/空记录、深浅主题、横竖屏、重建后阅读位置及系统返回不发送按键。[专项结果](test-results/history-ui-device.txt)。同轮其余 3 项真实回环 SSH/SFTP 回归亦通过，包含 Codex 从原按钮返回实时、连续插图、分享队列重建与关闭重连。
- 已目视检查组件的[深色竖屏](screenshots/history-ui-dark-portrait.png)与[浅色横屏](screenshots/history-ui-light-landscape.png)。最初的横屏组件测试因空白测试 Activity 重建后未重新挂载内容而失败，修正测试宿主后通过；真实 MainActivity 重建检查独立通过。
- 默认包名 Debug APK 与 AndroidTest 编译通过；Lint **0 错误、14 警告和 1 提示**。本轮为模拟器验证，APK 保存于 `.local/builds/iTermux-history-ui-debug.apk`。

## 2026-10-01 PR #6 评论修复与统一全屏历史

- Codex、Grok 等全屏程序统一浏览客户端在本次连接中捕获的输出；不再探测工具名或发送 Ctrl+T、Page Up、Escape。清屏/滚动前保留被覆盖文字，固定快照支持最早/最新跳转和复制，限制为 50,000 行或 2 Mi 个字符。连接前、未显示及已被容量限制淘汰的内容不在此记录中。
- 历史进入/滚动/退出串行并检查代次；退出完成后旧请求不会重新进入 copy-mode。常用命令退出历史后才发送，仍在全屏程序时拒绝发送命令与回车，草稿保留。
- 输入队列切换原子化；shell 历史通过 SFTP 偏移只读末尾最多 512 KiB。下载显式区分同名文件、保持扩展名，清理失败不吞掉取消。
- Activity 保存未交接的图片 URI 字符串；服务持有分享选择器状态，会话持有整个上传队列。tmux 关闭保留远端身份及卡片，显式移除时提示手动连接命令。字体导入在 IO 执行，以临时文件和 Typeface.Builder 验证，损坏文件不会覆盖已导入字体。
- JVM：app 43、ssh-core 42、terminal-emulator 150、terminal-view 18，合计 **253 项通过**；16 项未配置的外部环境测试跳过。含真实回环 SSH 的 11 项重连场景，以及真实 OpenSSH/tmux 滚动、退出后陈旧滚动不重入、SFTP 尾部读取测试。
- API 36 模拟器、隔离包 `io.itermux.pr6check`：**8 项通过**，覆盖不支持原生历史的 Grok 式全屏重绘、Codex 式同路径查看及三次插图、分享队列旋转、关闭后重连、损坏字体拒绝、命令执行、跨目录同名下载、tmux 拖动与 copy-mode 内执行命令。[设备测试原始结果](test-results/pr6-history-review-device.txt)。全屏程序使用隔离 PTY 夹具，未调用真实 Grok/Codex 模型服务。
- Debug/AndroidTest 构建通过；Lint 0 错误、14 警告和 1 提示（既有界面建议与依赖版本提示）。另复跑此前已失败的 `TerminalExtraKeysTest`，仍在本地滚动条拖回实时断言失败（期望 0，实际 -178）；该旧问题未计入 8 项专项通过结果。本轮未做真机验证。APK、截图和其余日志在忽略目录 `.local/builds/`。

## 2026-09-25 传输进度、目录与双语界面

- 本机隔离 SFTP 夹具验证同名文件上传到两个不同目录、同一目标拒绝覆盖、下载大小与字节数匹配；上传目录菜单在进入另一目录时立即更新目标路径。
- 模拟器通过语言切换与 Activity 重建、传输进度弹窗、远端目录菜单和 Android 文档流往返 4 项专项测试；原有导航、会话标题、终端快捷键 3 项回归也通过。已目视检查[英文设置](screenshots/language-settings-en-emulator.png)及[上传进度](screenshots/transfer-progress-en-emulator.png)截图。
- App、SSH、终端模块共 180 项单测通过，11 项外部环境相关测试跳过，0 失败；Debug、AndroidTest 构建通过；lint 0 错误、10 条版本及既有界面建议警告。
- PLU110 的现装包与本轮 Debug 包证书一致；已保留数据覆盖安装最新 `io.itermux.app`，安装包拉回后与本机构建逐字节相同。解锁后在真机上运行上述 4 项专项测试，全部通过：[真机记录](test-results/transfer-i18n-phone.txt)、[英文设置截图](screenshots/language-settings-en-phone.png)、[传输进度截图](screenshots/transfer-progress-en-phone.png)。测试只连接本机隔离 SFTP 夹具；主机数据未清除。


首次验收：2026-09-14；界面优化复验：2026-09-15。应用 ID `io.itermux.app`，最低 Android 8 / API 26，compile/target SDK 36。此记录区分实际通过的检查、测试替身和需用户补验的项目。

## 2026-09-25 重构与最新验证

以下为当前工作树的最新结果；后续章节保留此前各轮验收时的状态。

- 将主屏卡片/对话框、终端操作栏/原生回调、主机编辑草稿分别收拢；SSH 命令、连接层轮询和终端视图的远程历史状态各有独立职责。导航标签去重，保留原有页面入口与测试标签。
- 最新 Debug、AndroidTest 构建和 lint 通过；本地 JVM 测试 **179 项通过、0 失败**，另有 **11 项**因可选外部 OpenSSH 夹具缺席而跳过。lint 为 **0 错误、11 警告**。[Gradle 输出](test-results/refactor-gradle.txt)。
- 专用回环 sshd、独立 tmux socket 和测试密钥下，真实 `TmuxHistoryIntegrationTest` **1 项通过、0 跳过**；模拟器 `TmuxHistoryUiTest` 从进入历史、滑块拖动到返回实时画面 **1 项通过**。[集成测试输出](test-results/refactor-tmux-integration.txt)与 [JUnit XML](test-results/refactor-tmux-integration.xml)。测试夹具及端口映射已清理，未连接用户保存的主机。
- Android 16 模拟器的 `NavigationThemeTest`、`TerminalLayoutTest`、`TerminalExtraKeysTest`、`TerminalDraftTest`、`TmuxHistoryUiTest` 各通过 1 项。横屏布局用例已按现行 96 dp 双列侧栏更新断言；键盘展开时单列侧栏可滚动。
- PLU110 真机使用默认包名 `io.itermux.app` 同签名覆盖更新。`NavigationThemeTest`、`TerminalExtraKeysTest`、`TerminalDraftTest` 各通过 1 项：[导航](test-results/refactor-navigation-phone.txt)、[快捷键](test-results/refactor-extra-keys-phone.txt)、[草稿粘贴](test-results/refactor-draft-phone.txt)。测试组件已卸载，手机上仅保留 `io.itermux.app`；未清除已有应用数据。

## 2026-09-25 界面与终端视觉复验

- 主机搜索改为标题栏入口，默认不占列表高度；展开后按名称、地址和用户名筛选，关闭时清除筛选并收起输入法。深石墨色界面与终端背景一致，薄荷绿用于连接状态、光标和重点操作。终端默认字号由 14 sp 调为 13 sp，已有用户字号偏好保留；字号未变化时不再重复触发终端/PTY 尺寸更新。
- 操作栏展开后有栏顶“×”可直接收起，收起后右上角“⋯”可重新展开。保留显式固定、键盘切换、额外按键与横屏滚动。历史滚动条在阅读旧内容时留下低对比度滑块；轨道淡出后，仅滑块附近可抓取，避免右边缘大范围透明区域误抢手势。
- 根据真机反馈，tmux 模式新增“历史 / 实时”入口。进入后可下滑浏览远端旧记录、拖动右侧滑块定位，并返回最新画面；远端历史位置来自 tmux 当前窗格，长距离拖动使用顶部/底部跳转或翻页，避免逐行重绘。普通 SSH 的本地历史行为保留。
- Android 16 模拟器的隔离包名 `io.itermux.devicecheck` 连接本机回环 SSH 字节夹具，已验证中英文终端输出、拖动滑块到旧内容、追加新输出保持阅读位置、拖回实时输出、操作栏收起/重开与额外按键发送。主机页搜索从默认收起到筛选再收起也通过。结果见[最终模拟器输出](test-results/apple-redesign-final-emulator.txt)；视觉证据：[紧凑主机页](screenshots/apple-redesign-hosts-compact.png)、[搜索展开](screenshots/apple-redesign-hosts-search.png)、[连接终端](screenshots/apple-redesign-terminal.png)、[中英文历史与滑块](screenshots/apple-redesign-scrollbar.png)。
- 一加 PLU110 真机的隔离包连接本机独立 OpenSSH + tmux 3.5a 测试会话，进入历史、下滑、拖动到最早行、拖回底部、退出复制模式均通过；[真机测试输出](test-results/apple-redesign-tmux-phone.txt)、[远端协议测试](test-results/apple-redesign-tmux-core.xml)、[真机界面](screenshots/apple-redesign-tmux-ui-phone.png)、[终端旧内容](screenshots/apple-redesign-tmux-history-phone.png)。独立回环 SSH 字节夹具上的本地历史、额外按键、操作栏测试也[通过](test-results/apple-redesign-local-scroll-phone.txt)。搜索框移除裁切高度后，真机[筛选测试](test-results/apple-redesign-search-phone.txt)与[画面](screenshots/apple-redesign-search-phone.png)通过。
- App、终端解析器、终端视图和 SSH 核心的 JVM 测试共 175 项通过、10 项可选外部 OpenSSH 测试跳过；隔离 Debug 与 AndroidTest 构建通过，lint 0 错误、9 条依赖/构建工具提示。[Gradle 输出](test-results/apple-redesign-gradle.txt)。新设计尚未写入 `dist/` 交付 APK，原包名 `io.itermux.app` 未在真机上覆盖安装；tmux 真机验证针对隔离的 macOS OpenSSH/tmux 会话，未连接用户保存的服务器。

## 2026-09-25 终端额外按键

- 参考 Termux 官方默认额外键布局，为现有侧边操作栏增加 `Esc`、`Tab` 和一次性 `Ctrl`，保留原有会话、键盘、方向与退格操作。`Ctrl` 有选中反馈，在下一个键盘字符后复位。
- 使用只监听本机 `127.0.0.1:22228` 的 SSH 字节回显夹具与 USB/模拟器端口反向映射，不使用手机已有的真实主机。Android 16 模拟器端到端测试 **1 项通过**：`Esc → 1B`、`Tab → 09`、`Ctrl` 加 `c → 03`、随后 `d → 64`；Compose 截图见 [按键布局](screenshots/extra-keys-emulator.png) 和 [Ctrl 选中状态](screenshots/extra-keys-ctrl-armed-emulator.png)。
- 补充模拟器 IME `commitText` 路径：`Ctrl` 加 `e → 05`，下一个 `f → 66`；横屏逐一滚动到六个额外按键并确认可见。截图发现整栏滚动会藏起会话入口，因此改为顶部固定会话、键盘和菜单，仅额外按键滚动；复验 **1 项通过**，见[横屏滚动到底部截图](screenshots/extra-keys-landscape-emulator.png)。
- 在模拟器启用软键盘后复验：横屏键盘收起时固定顶部入口；键盘弹出、工作区高度缩小时自动允许整栏滚动，以维持额外按键完整触摸区域。`TerminalExtraKeysTest` 同时检查这两种状态，**1 项通过**，见[仪器测试输出](test-results/extra-keys-emulator.txt)；模拟器原有软键盘设置在测试后恢复。
- 修复菜单“粘贴”覆盖未发送草稿的问题：文字插入光标处或替换选区，超长剪贴板截断时不拆开 UTF-16 代理对。`DraftPasteTest` 的 3 个单元测试，以及真实界面中输入中文草稿、左移光标、收起编辑器、再粘贴到中间的 `TerminalDraftTest` 均通过。额外按键与草稿两项仪器测试在最终隔离构建上共同通过，见[合并测试输出](test-results/terminal-input-emulator.txt)。测试恢复了原剪贴板。
- App 与终端解析器单元测试共 **160 项通过、0 失败**；Debug、AndroidTest APK 编译通过；lint **0 错误、12 条既有警告**。
- 最终复验还运行了 `ssh-core` 的 17 项测试：7 项通过、10 项因缺少可选的外部 OpenSSH 凭据而跳过；本轮真机的本机回环 SSH 测试另行覆盖了实际网络会话。默认 Debug APK 清单仍为 `io.itermux.app`；`git diff --check` 通过。
- 一加 PLU110 真机安装独立包名 `io.itermux.devicecheck` 和测试包，原 `io.itermux.app` 及主机数据未被覆盖。最终两项仪器测试在真机 **2 项通过、0 失败**，见[测试输出](test-results/terminal-input-phone.txt)：触摸点击 `Esc → 1B`、`Tab → 09`；触摸点击一次性 `Ctrl` 后通过终端输入法接口提交 `c → 03`，接着 `d → 64`；再验证 `Ctrl+e → 05`、普通 `f → 66`。竖屏[按键实图](screenshots/extra-keys-phone.png)与[Ctrl 选中状态](screenshots/extra-keys-ctrl-armed-phone.png)均来自隔离测试 App；横屏[实际 View 截图](screenshots/extra-keys-landscape-phone.png)显示顶部会话、键盘、菜单入口保留，额外按键可滚动触达。用例还验证横屏弹出输入法后各键可触达，原草稿在中间粘贴后的文字保留。终端只连接本机回环 SSH 测试夹具。
- 真机厂商启动确认弹窗一度抢走 ADB 字符注入焦点，随后手机自动锁屏；排除这两项环境干扰后，上述最终用例通过。测试时临时设置 USB 供电保持亮屏，结束已恢复原值 0，并移除端口映射。原 App 的签名证书 SHA-256 为 `8603c441676382c21e54f994595b3cf5d4f9c5210cff6d11b2e39822bde60881`，与仓库旧手机测试包及当前本机调试签名均不同；本轮只验证独立包名，**未覆盖升级原 App**。

## 2026-09-16 输入法连续退格与默认交互

已保留数据覆盖安装到连接的一加 Turbo 6 / PLU110，安装包 SHA-256 与本次 `dist/iTermux-0.1.0-phone-test.apk` 一致：`c8a5e8dbb3af6b94b33a61c04078c79a02951d3082aec85457b253488613bae3`。Debug 与 Release 均沿用原证书。

- 操作栏默认隐藏，右上角 ⋯ 展开；显式固定偏好仍保留。点击终端输入区自动弹出输入法，返回键或键盘按钮可收起。
- 终端收到间隔不超过 250 ms、持续 1000 ms 的输入法退格后，用 Ctrl+U、Ctrl+K 清空当前输入；同次长按只清空一次。支持普通 DEL 事件、`deleteSurroundingText` 和码点版本。点按正常删字符，操作栏删除按钮不参与持续退格推断。
- 向输入法提供实际光标所在逻辑行的有限文字上下文，支持自动折行和 UTF-16 选区；未提交中文与远端文字分开处理。微信输入法的兼容依据来自此前在此手机对 3.5.3 的协议实测：长按产生约 100 ms 间隔的普通 DEL，没有有效长按标志，且需要 extracted text 响应。
- 新增长按识别单元测试 **4 项通过**，覆盖初始长按延迟、一次清空、持续抑制、松开间隔及新输入重置。[记录](test-results/ime-backspace-unit.xml)。
- Android 8：默认隐藏、点击弹键盘及完整横竖屏布局 **3 项通过**，[记录](test-results/ime-layout-android8.txt)。补充折行上下文后最终版本的三种退格协议、行中间清空、后续输入、中文组合、Activity 重建、草稿按钮和滚动条 **3 项通过**，[记录](test-results/ime-input-android8.txt)。
- Debug、Release、仪器测试 APK 构建通过；lint 0 错误、12 条既有警告。
- 手机解锁后，微信输入法 3.5.3 **真实触摸长按测试通过**：光标左侧 60 个字符、右侧 `RIGHT`，按住输入法删除键 2.4 秒，两侧均被清空；随后输入 `KEPT`，远端仅收到 `KEPT`，先前 `HISTORY_KEPT` 历史仍存在。测试还确认点击终端自动弹出输入法、操作栏保持隐藏。[测试记录](test-results/ime-hold-phone-physical.txt)、[结果截图](screenshots/ime-hold-phone-passed.png)。所有交互均在专用回环 SSH 账号执行，未向用户服务器发送测试命令。此前锁屏导致的未验项已完成补验。
- 同一手机补充交互回归 **3 项通过**：三种退格协议、单次删除及清空后的输入、默认隐藏与反复打开/关闭键盘、左右光标和长按删除按钮、草稿中文与表情编辑、横竖屏切换、滚动条拖动和自动隐藏。[记录](test-results/ime-input-phone.txt)。手机实测与交互回归合计 **4 项通过**，APK 与交付文件的 SHA-256 再次核对一致。

使用边界：连续退格是兼容推断，快速连续点按也可能触发。清空当前输入仍要求远端支持 Ctrl+U/Ctrl+K；未改写第三方输入法自身行为。下方保留此前版本记录，默认显示及键盘行为以本节为准。

## 2026-09-15 输入控制与自动隐藏滚动条

新版已保留数据覆盖安装到连接的一加 Turbo 6 / PLU110（Android 16），手机安装包与 `dist/iTermux-0.1.0-phone-test.apk` 的 SHA-256 一致。Debug、Release 沿用各自原签名证书。

- 操作栏与草稿框新增左右光标和删除按钮；删除点按删字符、长按清空当前输入，不清除终端历史。终端移动/删除前处理 IME 组合文字，清空时丢弃未提交文字，避免之后再次上屏。
- 草稿按完整字符边界移动和删除，验证中文、表情、组合重音和家庭表情；旋转后保留草稿，编辑不自动写入 SSH。
- 右侧本地历史滚动条可拖动；闲置 1.2 秒后淡出，隐藏后仍能拖动。验证上下边界、拖到底部恢复输出、查看旧内容时新输出保持阅读位置，以及自动隐藏后的像素恢复。
- Android 8 模拟器：新增功能及键盘开关复验 **2 项通过**，[记录](test-results/input-controls-android8.txt)。完整横竖屏布局回归与初次新增功能测试 **2 项通过**，[记录](test-results/input-layout-android8.txt)。
- 一加手机：新增功能和连续三次键盘开关复验 **2 项通过**，[记录](test-results/input-controls-phone.txt)。所有输入和输出来自隔离回环 SSH 测试账号，未向用户真实服务器发送测试命令。
- Debug / Release / 仪器测试 APK 构建通过；lint 0 错误、12 条既有警告；149 项终端解析器单元测试通过。

使用边界：终端清空采用 Ctrl+U、Ctrl+K，远端程序需支持相应快捷键。滚动条反映本地历史，tmux/vim 等全屏程序的历史仍由远端滚动或复制模式处理。

截图：[手机竖屏编辑](screenshots/input-controls-phone-portrait-editor.png)、[手机横屏编辑](screenshots/input-controls-phone-landscape-editor.png)、[手机历史滚动条](screenshots/input-controls-phone-scrollbar.png)。下方保留此前界面的验收记录，新增输入与滚动行为以本节为准。

## 此前界面

连接页横竖屏默认用满可用区域，隐藏状态栏和系统导航栏；边缘滑动可临时呼出。右侧悬浮操作栏默认展开，按钮沿终端滚动条方向竖排，轻触终端保持展开。菜单中的“固定操作栏”可固定为右侧 48 dp 栏，偏好持久保存；“隐藏操作栏”取消固定并隐藏按钮，右上角“⋯”用于恢复。终端内容延伸到摄像头开孔周围，仅操作按钮单独避让开孔；自由窗口的系统标题栏仍会避让。回到主机页恢复系统栏。

默认隐藏键盘，轻触终端不弹键盘。键盘按钮第一次点击打开输入法，再次点击收起，输入法开关不移动按钮。快捷键入口、面板和 Ctrl 锁定状态已删除。横屏编辑框与发送、回车等动作排列在同一行；编辑时暂时收起操作栏，关闭编辑框后恢复。键盘打开时限制编辑框高度，关闭后重新应用沉浸显示。主机页横竖屏均合并搜索与添加按钮，竖屏移除重复的大标题，横屏合并标题和导航，列表按可用宽度自动分栏。

2026-09-15 按用户要求移除语音按钮，改从更多菜单打开“编辑文本”，点击编辑框输入。文字先进入草稿；发送文本和回车独立操作。

操作栏调整时的验证：Debug / Release APK 构建、Android 仪器测试 APK 编译、`lintDebug` 均通过。Android 8 和 Android 17 的布局回归均通过，覆盖默认展开、竖排、轻触保持展开、固定与重建、手动隐藏和恢复、编辑框开关，以及横竖屏浮动和固定状态各三次键盘开关（包含系统返回键）。触摸测试同时检查按钮位置稳定、键盘关闭后不反弹，以及没有快捷键面板和菜单项。记录：[Android 8](test-results/controls-android8.txt)、[Android 17](test-results/controls-android17.txt)。

连接的 PLU110 手机使用微信输入法（`com.tencent.wetype`），键盘专项触摸测试通过：连续三次打开/收起（包含系统返回键），收起后轻触终端不重新弹出键盘，操作栏保持展开。记录：[手机键盘复验](test-results/controls-phone.txt)。新版已保留数据覆盖安装到手机；Debug / Release 交付 APK 均通过签名校验，证书与各自旧包一致，SHA-256 已更新。

![横屏终端与默认展开的竖排操作栏](screenshots/controls-layout-landscape-floating.png)

![竖屏终端与默认展开的竖排操作栏](screenshots/controls-layout-portrait-floating.png)

尺寸记录：[Android 8](test-results/controls-measurements-android8.txt)、[Android 17](test-results/controls-measurements-android17.txt)。截图中的主机及终端输出来自隔离测试环境；只在测试中临时取消安全截图标记。以下章节保留较早版本的验收记录，相关操作栏、快捷键和语音行为以本节为准。

## 2026-09-15 终端顶部留白修正

PLU110 的窗口报告摄像头安全区域为顶部 141 px。此前终端根布局对整个页面应用 `displayCutout`，即使隐藏系统栏也会留下整条空白；现改为终端内容延伸到屏幕边缘，仅操作按钮避让开孔。布局断言改为检查终端顶部和左侧没有开孔边距。

Debug / Release 构建、仪器测试编译及 `lintDebug` 通过。Android 17 启用模拟摄像头开孔后，完整布局和键盘回归通过：默认竖屏终端为 1080 × 1920，横屏为 1920 × 1080，与整个窗口尺寸一致；固定栏仅占右侧宽度。记录：[测试输出](test-results/fullscreen-cutout-android17.txt)、[尺寸记录](test-results/fullscreen-cutout-measurements.txt)、[竖屏截图](screenshots/fullscreen-cutout-layout-portrait-floating.png)、[横屏截图](screenshots/fullscreen-cutout-layout-landscape-floating.png)。

交付 APK 已更新并通过原证书签名校验，新版已保留数据覆盖安装到手机。此次手机处于锁屏状态，未完成新版的真机界面复验；上方手机键盘结果属于此前操作栏修改版本。

## 2026-09-15 悬浮操作栏与空间利用优化

在 1080 × 1920、无开孔模拟器上，默认竖屏终端由 1080 × 1794 扩展为 1080 × 1920，横屏由 1794 × 1080 扩展为 1920 × 1080；相较原来的默认固定栏，终端面积增加约 7%。展开悬浮操作栏后终端尺寸保持不变，只有浮层所在的小块区域被遮挡。编辑时不再叠加操作栏，竖屏省下 48 dp 高度，横屏省下 48 dp 宽度。

本次布局回归覆盖默认全屏、展开及轻触收起悬浮栏、固定及 Activity 重建、横竖屏切换、键盘缩放终端、中文多行草稿、收起编辑后恢复全屏、返回主机页恢复系统栏。既有回归继续覆盖真实 SSH、中文组合输入、远端 Codex 菜单及主机保存与搜索。测试为模拟器临时开启软键盘并关闭首次全屏教学浮层，结束后恢复原设置。

- Debug、Release APK 构建通过；lint 0 错误，12 条警告（依赖版本提示及 SharedPreferences 的 KTX 风格建议）。
- Android 8 与 Android 17 各通过 3 项界面回归：[Android 8 输出](test-results/floating-android8.txt)、[Android 17 输出](test-results/floating-android17.txt)。
- Android 17 额外启用模拟打孔屏后，全套布局测试通过，确认只避让开孔所需边距：[打孔屏输出](test-results/floating-cutout-android17.txt)。
- 完整尺寸记录：[Android 8](test-results/floating-measurements-android8.txt)、[Android 17](test-results/floating-measurements-android17.txt)、[模拟打孔屏](test-results/floating-measurements-cutout-android17.txt)。

尺寸与窗口边距仍按 [Android 窗口边距说明](https://developer.android.com/develop/ui/compose/system/insets)避让开孔、窗口标题栏与输入法；第三方输入法和设备开孔会影响实际可用区域。本次未在已锁屏的 PLU110 上运行界面测试。

## 2026-09-15 前一版固定操作栏复验

- Debug、Release APK 构建通过；lint 0 错误、10 条既有警告。
- Android 8 / API 26 和 Android 17 / API 37.1 均通过新增的全屏布局测试，覆盖横竖屏、终端尺寸与远端 PTY 更新、横屏键盘、中文多行草稿不自动发送、隐藏/恢复操作栏、旋转后保持隐藏状态、返回主机页恢复系统栏。
- 两版系统的既有界面回归测试通过，包括中文组合输入、Activity 重建、语音草稿、真实远端 Codex 菜单、主机添加与搜索。Android 8 的旧系统栏可见性由兼容 API 估算，测试结合原生隐藏标志、实际窗口尺寸与截图验证。
- 自动化测试仅在模拟器启用软键盘并抑制首次全屏教学提示，测试结束恢复原设置。生产应用保留 Android 正常教学提示，不修改系统设置。

两个 1080 × 1920、无开孔模拟器的实测结果一致：

| 模式 | 终端像素尺寸 | 屏幕利用率 |
| --- | --- | --- |
| 竖屏，操作栏可见 | 1080 × 1794 | 宽度 100%，高度 93.4% |
| 横屏，操作栏可见 | 1794 × 1080 | 宽度 93.4%，高度 100% |
| 隐藏操作栏 | 与屏幕相同 | 终端区域 100%；右上角保留一个小型恢复按钮 |

原始布局数据：[Android 8](test-results/layout-measurements-android8.txt)、[Android 17](test-results/layout-measurements-android17.txt)。测试输出：[Android 8 布局复验](test-results/layout-android8.txt)、[Android 17 布局与界面回归](test-results/layout-android17.txt)。设备开孔和第三方输入法高度会影响实际可用空间；本次没有执行微信输入法的真实语音识别。

## 首版自动化结果（2026-09-14）

| 范围 | 结果 | 验证内容 |
| --- | --- | --- |
| terminal-emulator JVM | 149 通过，0 失败，0 跳过 | UTF-8 分片、中文与 emoji 宽度、颜色、控制序列、键码、滚动、尺寸变化、历史上限、bracketed paste、OSC 52 禁写剪贴板 |
| ssh-core JVM + OpenSSH | 17 通过，0 失败，0 跳过 | 信任策略 6 项、真实连接及异常分支 11 项 |
| Android 8 / API 26 模拟器 | 8 通过 | Keystore、Room、四种认证、终端/输入/页面/生命周期组合测试 |
| Android 17 / API 37.1 模拟器 | 8 通过 | 同上，使用 16 KiB 页面系统镜像 |
| 两个模拟器的进程死亡测试 | 各 1 项恢复断言通过 | 先连接普通 SSH 和 tmux，外部终止整个应用进程，再检查持久化和显式恢复 |
| lint | 0 错误，10 警告 | 保留固定依赖版本更新提示和非阻断风格建议 |
| 正式签名 APK 安装 | 两个模拟器通过 | 全新安装 code 1 → 同证书覆盖升级 code 2 → 强制停止再启动；最后恢复交付的 code 1 |

原始记录见 [Android 8](test-results/android8.txt)、[Android 17](test-results/android17.txt)、[单元与 SSH 逐项结果](test-results/unit-and-ssh.json)、[Android 8 进程重建](test-results/process-death-android8.txt)、[Android 17 进程重建](test-results/process-death-android17.txt)、[安装升级](test-results/install-upgrade.txt)。

真实连接覆盖：密码、keyboard-interactive、加密 Ed25519、加密 RSA、错误密码分类、取消首次信任且不存指纹、主机公钥变化阻断、静默连接超时、已连接网络断开后拒绝输入、PTY 101×37、tmux 重附着、Ctrl+C 中断任务、vim 启动和退出。缺失 tmux 分支使用第二个隔离 OpenSSH 端点强制让探测命令返回失败；没有卸载服务器原有 tmux。

设备测试通过实际 InputConnection 验证中文组合输入未提交前不向远端发送拼音、提交后可显示中文；通过编辑框提交模拟语音识别完成后的文字，确认它仅进入草稿、不自动发送。没有用合成测试替代真实语音识别质量的声明。默认键盘隐藏、编辑框收起后隐藏键盘、轻触终端不弹键盘以及终端高度超过根视图 80% 均有断言。

旋转检查确认终端列数随横竖屏增减且连接保持。后台检查解除测试和 Activity 绑定，确认服务仍处于前台状态且 SSH 已连接。锁屏检查为约 1.2 秒的模拟器熄屏/唤醒，不能代表长时间 Doze。进程重建检查确认新服务会话列表为空，普通 SSH 不显示为恢复；Room 中主机和 Keystore 密文仍可读取，手动重连 tmux 后保留的 shell 变量仍在。

## 真机

设备报告型号 PLU110，Android 16 / API 36，通过已授权 USB 调试安装。界面调整前，6 项存储/认证测试及 2 项终端/主机界面测试均通过。新版覆盖安装成功，保留原开发签名及 2 个主机，没有卸载或清除用户数据。

新版的 6 项存储/认证检查通过；最新界面复验在启动阶段受手机锁屏阻挡，尚待用户解锁后完成。当前输入法为微信输入法。实际麦克风录音、识别准确率及该手机长时间后台行为尚未完成验收；用户选择继续使用微信输入法语音，默认收起键盘。

## 远端环境与 AI 工具

使用隔离的 WSL Ubuntu 22.04 测试账号 `itermux_test`，OpenSSH 8.9p1 Ubuntu-3ubuntu0.16、tmux 3.2a、Vim 8.2。测试端口仅监听 Linux 回环地址，经 adb reverse 连接手机/模拟器。测试使用独立随机密码及公开测试私钥，没有使用用户真实主机凭据，也没有更改系统 sshd 配置。

实际运行官方 Codex CLI **0.154.0** Linux musl 版本，下载 URL 与 SHA-256 在 `test-tool.lock.json`。终端执行 `codex --version`，启动交互登录菜单，发送方向键并退出。未登录 AI 账号、未调用模型、未验证模型生成代码；这部分需用户已有远端账号补验。Codex 二进制不包含在 APK 或源码包中。

## 签名与文件

`dist/iTermux-0.1.0.apk` 是独立发布签名版本，RSA 3072 位，APK v2 签名校验通过。发布证书 SHA-256：

```text
ed63b0c6b646f8bfb5446ad89e51e5a558fcd6ac9cd8bc28d788431803117a71
```

`dist/iTermux-0.1.0-phone-test.apk` 与手机现有开发签名相同，用于保留数据覆盖更新。开发签名的私钥原件与单独备份位置在 README 中；独立发布密钥和 `signing.properties` 均排除在源码包外。

APK 的 SHA-256 见 `dist/SHA256SUMS.txt`，签名验证输出见 `dist/signature.txt` 和 `dist/phone-test-signature.txt`。源码归档包含构建脚本、固定版本、终端库、许可和本测试记录，排除 SDK、缓存、签名密钥、随机测试密码、Codex 二进制及 APK。

## 后续补验

- 解锁 PLU110 后复验新版界面和微信输入法麦克风的真实听写；外接实体键盘及选择复制手势仍需真机操作覆盖。
- 使用自己的远端 AI 账号完成一次真实模型请求。
- 在实际 VPN、移动网络/Wi-Fi 切换、长时间锁屏和厂商省电策略下补验；本次网络中断使用可控 TCP 断流测试。
- 当前同签名升级只提高版本号，未涉及未来数据库 schema 变更；后续 Room 迁移需随变更单独验收。
