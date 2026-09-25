# 横竖屏布局评估（Apple Design 原则）

本应用运行在 Android 上。这里借用 Apple Human Interface Guidelines 的布局原则评估信息层级、可触达性、方向适配与状态连续性，不将 Android 界面伪装成 iOS 控件。

| 页面 | 竖屏评估 | 横屏发现与调整 |
| --- | --- | --- |
| 主机与会话 | 标题、切换入口、主要操作的层级清楚；保留单列阅读与自适应网格。 | 顶部与内容区重复占高，卡片过高，旋转后首行可能被截断。标题和操作收进同一工具栏，主机卡片改为紧凑排列，网格切换列数时对齐到卡片顶部。 |
| 主机编辑 | 单列字段适合窄屏；保持原有字段顺序。 | 全宽字段延长视线移动，认证与连接信息不易同时看到。宽屏改为连接/系统与认证/模式两列，选项可换行；旋转时保留未提交的字段和密钥导入状态。 |
| 终端 | 内容占据主要空间，底部快捷键保持原有布局。 | 单列侧栏需要滚动才能找到 Esc、Tab 等常用键；旋转时终端页面可能退回会话列表。足够高度且键盘收起时使用两列侧栏，并依据会话服务的真实状态保持当前终端。键盘展开或高度不足时使用可滚动单列。 |

按 [Layout](https://developer.apple.com/design/human-interface-guidelines/layout)、[Toolbars](https://developer.apple.com/design/human-interface-guidelines/toolbars)、[Segmented controls](https://developer.apple.com/design/human-interface-guidelines/segmented-controls) 和 [Accessibility](https://developer.apple.com/design/human-interface-guidelines/accessibility) 检查。实际实现保留 Android Compose 的组件、系统安全区域和输入法行为。

## 真机画面

PLU110 上拍摄：[竖屏编辑](screenshots/adaptive-editor-portrait-phone.png) · [横屏编辑](screenshots/adaptive-editor-landscape-phone.png) · [竖屏终端](screenshots/adaptive-terminal-portrait-phone.png) · [横屏终端](screenshots/adaptive-terminal-landscape-phone.png)。主机页横竖屏交互由下方导航测试覆盖。

## 验证

- `NavigationThemeTest`：竖屏/横屏主机、编辑草稿与密码跨旋转保留、主题和终端入口；PLU110 与模拟器各通过 1 次。
- `TerminalExtraKeysTest`：横屏快捷键可见及实际输入字节；PLU110 与模拟器各通过 1 次。
- 测试使用独立包名 `io.itermux.devicecheck`，没有覆盖手机上原有签名的应用。

测试输出：[真机导航](test-results/adaptive-navigation-phone.txt) · [真机终端](test-results/adaptive-terminal-phone.txt) · [模拟器导航](test-results/adaptive-navigation-emulator.txt) · [模拟器终端](test-results/adaptive-terminal-emulator.txt)。
