# Google Play 上架清单

本文列出把 Roamtty（原名 iTermux）发布到 Google Play 还需完成的工作，按执行顺序排列。现状核对基于 `main@b34f995`（2026-10），并随后续改动更新。

## 现状

| 项目 | 现状 | 是否满足 Play 要求 |
| --- | --- | --- |
| 包名 | `com.roamtty.app`（对应已持有的 `roamtty.com`） | 满足；首次发布后不可修改 |
| targetSdk / minSdk | 36 / 26 | 满足（新应用须 ≥ 35） |
| 发布格式 | `scripts/release.sh` / `release.ps1` 同时产出签名 APK 与 AAB | 满足 |
| 签名 | 本地 3072 位 RSA 发布密钥（`.private/signing`） | 可作为上传密钥；需启用 Play 应用签名 |
| versionCode | 由 `gradle.properties` 的版本号推导（0.1.0 → 100） | 满足；每次上传前递增版本号 |
| 16 KB 页面 | 唯一原生库 `libandroidx.graphics.path.so` 的 LOAD 段对齐为 `0x4000` | 满足 |
| 前台服务 | `specialUse` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` | 需在 Play Console 提交声明并通过审核 |
| 其他权限 | INTERNET、网络状态、WAKE_LOCK、POST_NOTIFICATIONS | 无需额外声明 |
| 数据收集 | 无账号、无广告、无遥测、无第三方 SDK | 数据安全表单可如实填写“不收集” |
| 隐私政策 | `docs/PRIVACY.md`（中英文），应用「帮助 → 隐私政策」可查看，公开网址 https://roamtty.com/privacy/ | 满足；官网见 `docs/WEBSITE.md` |
| 明文流量 / 备份 | `usesCleartextTraffic=false`、`allowBackup=false` | 满足 |
| 商店素材 | 无 | **缺失** |

## 1. 开发者账号

1. 在 [Play Console](https://play.google.com/console) 注册开发者账号，支付一次性 25 美元注册费。
2. 选择账号类型：
   - **个人账号**：需完成身份验证；2023-11-13 之后注册的个人账号，首次发布正式版前必须完成一次**封闭测试**：至少 12 名测试者连续选择加入 14 天，然后才能申请正式版访问权限。
   - **组织账号**：需要 D-U-N-S 编号，可跳过 12 人 / 14 天的测试门槛，但开源个人项目一般没有对应法人。
   - 建议：以个人账号起步，提前组织 12 名以上测试者。
3. 填写公开的开发者联系邮箱（商店页面会展示），并在账号中启用两步验证。

## 2. 构建与签名

1. **使用现有发布脚本**：运行 `scripts/release.sh`（Linux / macOS）或 `scripts/release.ps1`（Windows），同时构建签名 APK 与 AAB，输出 `dist/Roamtty-<版本>.apk`、`dist/Roamtty-<版本>.aab`，校验签名并生成 `dist/SHA256SUMS.txt`。
   - AAB 产出已实现；发布前仍需用 `bundletool build-apks --mode=universal` 从该 AAB 生成通用 APK 做安装冒烟测试，确认 R8 规则（`app/proguard-rules.pro`）对 JSch / Bouncy Castle 仍然有效。这项验证尚未由发布脚本自动化。
2. **Play 应用签名**：首次上传时启用 Play App Signing（新应用强制）。两种做法任选其一：
   - 由 Google 生成应用签名密钥，现有 `itermux-release.p12` 作为**上传密钥**（推荐，上传密钥丢失可申请重置）；
   - 或把现有密钥导入为应用签名密钥，以保持与 GitHub 发布的 APK 同签名、可互相覆盖升级。如需 GitHub APK 与 Play 版互通升级，必须选这一项，并用 `pepk` 工具导出加密密钥。
   - 决定后在文档中注明：Play 版与 GitHub 直装版签名是否一致，避免用户覆盖安装失败。
3. **版本号约定**：`versionCode` 每次上传必须严格递增，且不能复用。建议规则 `major*10000 + minor*100 + patch`（如 0.1.0 → 100），或在 CI 中使用提交计数；`versionName` 与 Git tag 对应。发布前去掉 `-PitermuxApplicationId` 之类的测试覆盖参数。
4. **发布前检查**：
   - `./gradlew :app:lint :app:testDebugUnitTest :ssh-core:test :terminal-emulator:testDebugUnitTest :terminal-view:testDebugUnitTest`
   - 在 Android 8.0、Android 14、Android 15/16 真机或模拟器各装一次 release 包，验证连接、tmux 重附着、mosh 回退、SFTP 上传下载、前台通知及断开释放。
   - 确认 release 包中没有调试日志、测试私钥和 `test_password` 资产。
5. （可选）上传 R8 `mapping.txt` 到 Play Console，便于阅读 Android vitals 中的崩溃堆栈；AGP 打 AAB 时会自动附带。

## 3. 隐私政策与数据安全

1. **隐私政策网址**（必填，即使不收集任何数据）：
   - 新增 `docs/PRIVACY.md`（中英文），内容覆盖：不收集、不上传任何个人数据；主机信息与凭据只保存在本机并用 Keystore 加密；连接只发往用户指定的服务器；无广告、无分析、无崩溃上报；系统备份已关闭；卸载应用即删除全部数据；联系邮箱。
   - 已由 `scripts/site.py` 发布到 https://roamtty.com/privacy/（见 `docs/WEBSITE.md`），在 Play Console「应用内容 → 隐私权政策」中填写该网址。
2. **数据安全表单**（「应用内容 → 数据安全」）：
   - 是否收集或分享用户数据：**否**（数据仅在设备本地处理、未发送到开发者或第三方，不算“收集”）。
   - 传输加密：SSH / mosh 均加密，可勾选“传输中加密”（仅在声明收集时需要，按表单流程填写）。
   - 应用中没有账号体系，无需提供账号删除入口。
3. **广告声明**：选择「不含广告」。

## 4. 权限与政策声明

1. **前台服务声明**（「应用内容 → 前台服务权限」）：
   - 类型：`specialUse`。说明用途：用户主动发起的 SSH / mosh 终端连接需要在后台保持，最后一个连接断开即停止服务。
   - 需要录制一段短视频（上传到 YouTube，不公开链接即可）：打开应用 → 连接主机 → 切到后台 → 通知栏显示连接 → 断开后通知消失。
   - 审核若认为 `specialUse` 不合适，备选方案是评估 `dataSync`（Android 15 起有 6 小时限制，不适合长会话）或 `remoteMessaging`；当前判断 `specialUse` 最贴合，以说明和视频为主争取通过。
2. **唤醒锁**：`PARTIAL_WAKE_LOCK` 只在活动连接期间持有，Android vitals 会统计“过多部分唤醒锁”，上架后需关注该指标。
3. **电池优化**：应用只跳转到系统设置，未声明 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`，无需额外声明，保持不变。
4. **金融功能、健康、新闻、政府应用、COVID 等声明**：均选「否」。

## 5. 内容分级与目标受众

1. **内容分级**（IARC 问卷）：类别选「实用工具 / 生产力」；无暴力、色情、赌博；用户之间无交互、无共享位置。预计分级为所有人 / 3+。
2. **目标受众**：选择 18 岁以上（或 13 岁以上），不面向儿童，避免触发「家庭政策」的额外要求。
3. **应用访问权限**：核心功能需要一台 SSH 服务器。为审核员准备：
   - 一台专用演示服务器（低权限账号、受限 shell、可随时重置），在「应用访问权限」中填写主机、端口、用户名、密码和简短操作步骤；
   - 说明首次连接需确认主机指纹。
   - 没有演示服务器的话，审核很可能因“无法测试核心功能”被拒。

## 6. 商店页面素材

| 素材 | 规格 | 说明 |
| --- | --- | --- |
| 应用名称 | ≤ 30 字符 | 如 `Roamtty - SSH 终端` / `Roamtty - SSH & tmux Terminal` |
| 简短说明 | ≤ 80 字符 | 突出“免费开源、tmux 会话保持、mosh、SFTP” |
| 完整说明 | ≤ 4000 字符 | 基于 README「功能」与「隐私」整理 |
| 应用图标 | 512×512 PNG，32 位，≤ 1 MB | 从现有 launcher 图标导出高分辨率版本 |
| 置顶大图 | 1024×500 PNG/JPG | 新设计 |
| 手机截图 | 2–8 张，16:9 或 9:16，短边 ≥ 1080 px 更利于推荐 | 主机列表、AI Agent 会话列表、终端（tmux）、操作栏、SFTP 文件预览 |
| 平板截图 | 可选，7" / 10" | 如需在平板上展示则补充 |
| 分类 / 标签 | 工具 | 标签选「开发者工具」等 |
| 联系方式 | 邮箱必填，网站可选 | 邮箱 `support@roamtty.com`，网站 https://roamtty.com/ |

注意事项：

- 语言：默认语言设为简体中文，再添加英文翻译，两套文案与截图都要准备。
- **商标与元数据政策**：商店文案不要用第三方商标做关键词堆砌或暗示合作。README 中的「Claude Code、Codex」等表述不宜原样进入商店页面，可写“适合在手机上使用远程 AI 编程工具”。
- **名称**：为避开与 Termux、iTerm2 的名称相似风险，应用已从 iTermux 更名为 Roamtty。正式提交前在 USPTO、EUIPO 与中国商标网第 9 类查询一次“ROAMTTY”。
- 截图中不要出现真实服务器地址、用户名或私钥内容。

## 7. 测试轨道与发布

1. **内部测试**：上传第一个 AAB，添加自己和核心测试者（最多 100 人），确认安装、升级和 Pre-launch report（预发布报告）无崩溃。
2. **封闭测试**（个人账号必需）：
   - 创建测试轨道，邀请 ≥ 12 名测试者（Google 群组或邮箱列表），保证他们**连续 14 天**保持选择加入；
   - 期间至少再发布 1–2 个更新版本，并记录测试反馈，申请正式版时问卷会问到测试情况和改进内容。
3. **申请正式版访问权限**：满足条件后在 Play Console 提交问卷。
4. **正式发布**：
   - 国家 / 地区：Google Play 不在中国大陆提供服务，按需选择其他地区；
   - 使用分阶段发布（如 10% → 50% → 100%），观察 Android vitals 中的崩溃率与 ANR 率（阈值分别为 1.09% 与 0.47%）；
   - 首次审核通常需要数天，预留时间。
5. **出口合规**：应用使用标准加密算法（SSH），Play 没有单独问卷，但发布到所有国家时开发者自行承担合规责任；作为公开的开源软件，一般适用美国 EAR 的公开可用豁免，必要时在仓库中记录说明。

## 8. 后续维护

- 每年 8 月底前把 `targetSdk` 提升到 Google 要求的最新级别，否则新用户无法搜索到更新版本。
- 定期查看「政策状态」页面和开发者邮箱，按时回复政策声明更新（如前台服务、数据安全表单年度确认）。
- 关注 Android vitals：崩溃率、ANR、过多唤醒锁、过多后台网络使用。
- 可选：用 Gradle Play Publisher 或 fastlane `supply` 在 CI 中自动上传 AAB 与商店文案，商店文案和截图存放在仓库中统一维护。
- 可选：接入 Play In-App Updates 提示升级（会引入 Play Core 依赖，需评估与“无第三方 SDK”定位是否一致）。

## 待办汇总

| # | 事项 | 类型 | 状态 |
| --- | --- | --- | --- |
| 1 | 注册 Play 开发者账号并完成身份验证 | 账号 | 待办 |
| 2 | 决定签名策略（Google 生成 vs 导入现有密钥）并备份上传密钥 | 决策 | 待办 |
| 3 | 发布脚本产出 AAB，确定 versionCode 递增规则 | 代码 | 已完成 |
| 4 | 编写 `docs/PRIVACY.md` 并发布到公开网址，应用内可查看 | 代码 / 文档 | 已完成：https://roamtty.com/privacy/ |
| 5 | 搭建审核用演示 SSH 服务器 | 运维 | 待办 |
| 6 | 录制前台服务说明视频 | 素材 | 待办 |
| 7 | 制作图标 512px、置顶大图、中英文截图和文案 | 素材 | 待办 |
| 8 | 填写数据安全、内容分级、目标受众、应用访问权限、广告、前台服务声明 | Console | 待办 |
| 9 | 内部测试 → 封闭测试（≥ 12 人 × 14 天） | 测试 | 待办 |
| 10 | 申请正式版，分阶段发布并监控 vitals | 发布 | 待办 |
| 11 | 应用更名为 Roamtty，包名 `com.roamtty.app` | 代码 | 已完成 |
| 12 | 用 bundletool 从 AAB 生成通用 APK 并完成安装冒烟测试 | 测试 | 待办；发布脚本尚未自动化 |
