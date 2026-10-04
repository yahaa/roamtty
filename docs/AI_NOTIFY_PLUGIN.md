# AI 通知插件方案

> 状态：待评审 · 2026-10-04

## 1. 目标

Claude Code、Codex、Grok 在远端运行时，出现以下两种情况就在手机上弹出系统通知，点击通知直接进入对应会话：

- **需要处理**：等待权限确认，或停下来等用户输入。
- **任务完成**：一轮任务正常结束，或因错误中断。

## 2. 架构

```
远端 CLI hook ──> ~/.itermux/bin/itermux-notify ──> 追加一行到 ~/.itermux/events.jsonl
                                                          │
App（会话已有的 SSH 连接）exec: tail -F events.jsonl <─────┘
     └─> 解析 → 匹配会话 → Android 通知 → 点击打开会话
```

不使用终端转义序列（OSC 9 或 OSC 777），原因有三：

- mosh 不转发未知的 OSC 序列。
- tmux 默认会吞掉这些序列。
- hook 进程不一定有 tty。

mosh 模式下 App 本来就保留着 SSH 连接（用于传文件和读历史），事件通道复用这条连接即可。

## 3. 三个 CLI 的 hook 能力（已核对）

本机核对的版本：Claude Code 2.1.289、Codex 0.160.0、Grok 1.0.46。

| | Claude Code | Codex | Grok |
|---|---|---|---|
| 配置文件 | `~/.claude/settings.json` | `~/.codex/config.toml` 的 `notify`，以及 `~/.codex/hooks.json` | `~/.grok/hooks/itermux.json`（Grok 也会读 `~/.claude/settings.json`） |
| 需要处理 | `Notification`，匹配 `permission_prompt`、`idle_prompt` | `PermissionRequest`（hook） | `Notification`，匹配 `permission_prompt`、`idle_prompt` |
| 任务完成 | `Stop` | `notify` 的 `agent-turn-complete` 事件 | `Stop`，出错时为 `StopFailure` |
| 启用条件 | 写入配置即生效 | `notify` 写入即生效；hook 需要用户在 `/hooks` 里**信任一次** | 全局 hook 默认就信任 |
| 怎么区分来源 | stdin 里的字段是 `hook_event_name` | `notify` 的 argv[1] 是 JSON；hook 的 stdin 字段是 `hook_event_name` | 环境变量 `GROK_SESSION_ID`；stdin 字段是 `hookEventName` |

参考：本机文档 `~/.grok/docs/user-guide/10-hooks.md`、[Codex Hooks](https://learn.chatgpt.com/docs/hooks)。

## 4. 远端部分

### 4.1 事件格式（每行一个 JSON）

```json
{"v":1,"ts":1791100000,"agent":"claude","kind":"attention","tmux":"itermux-ab12","cwd":"/home/u/proj","msg":"Claude needs your permission to use Bash"}
```

- `agent`：`claude`、`codex` 或 `grok`。
- `kind`：
  - `attention`：需要处理（权限确认）。
  - `idle`：停下来等待用户输入（`idle_prompt`）。
  - `done`：完成。
  - `error`：Grok 的 `StopFailure`。
- `tmux`：CLI 运行在 tmux 里时，用 `tmux display -p '#S'` 取到的会话名；不在 tmux 里时为空。
- `msg`：截断到 200 字符。

### 4.2 `itermux-notify` 脚本

- 用 POSIX `sh` 编写，**无论什么情况都 `exit 0`**，耗时不超过 1 秒，不影响 CLI 本身。
- 输入来源有两种：stdin 收到的 hook JSON，或者 argv[1] 收到的 Codex `notify` JSON。
- 判断来源的顺序：
  1. 有 `GROK_SESSION_ID` 就是 Grok。
  2. 否则 argv[1] 里有 `agent-turn-complete` 就是 Codex。
  3. 否则 stdin 里有 `PermissionRequest` 也是 Codex。
  4. 其余情况都算 Claude。
- 解析 JSON：有 `python3` 就用它，没有就用 `sed` 提取需要的几个字段。
- 事件文件超过 256 KiB 时只保留最后 200 行，原子替换。

### 4.3 写入的配置（用 `$HOME` 展开后的绝对路径）

**Claude**：合并进 `~/.claude/settings.json`。

```json
{"hooks":{
  "Notification":[{"matcher":"permission_prompt|idle_prompt","hooks":[{"type":"command","command":"/home/u/.itermux/bin/itermux-notify","timeout":5}]}],
  "Stop":[{"hooks":[{"type":"command","command":"/home/u/.itermux/bin/itermux-notify","timeout":5}]}]
}}
```

**Grok**：写入独立文件 `~/.grok/hooks/itermux.json`。

- 内容与 Claude 的相同，另外加一个 `StopFailure`。
- **命令字符串必须和 Claude 的完全一致**：Grok 会对相同的 handler 去重，这样不会因为同时读了 Claude 配置而触发两次。

**Codex**：

- 在 `config.toml` 中写入 `notify = ["/home/u/.itermux/bin/itermux-notify"]`。
  - `notify` 是顶层键，必须插在第一个 `[table]` 之前。
  - 用户已经配置过 `notify` 时**不覆盖**，在面板里提示用户。
- 在 `hooks.json` 中合并一个 `PermissionRequest` hook，结构同上。
- **不注册 Codex 的 `Stop` hook**，否则会和 `notify` 重复通知。

### 4.4 安装、检测与卸载（扩展现有的“安装插件”）

- **检测**：在 `HostPluginProbe` 里加一行，检查 `~/.itermux/bin/itermux-notify` 是否存在且可执行。同时检测 `claude`、`codex`、`grok` 哪些已安装，以及各自的 hook 是否已经注册。
- **安装**：
  - 只写用户目录，不需要 sudo。
  - 只为已安装的 CLI 写配置。
  - 修改前先备份为 `*.itermux.bak`。
  - JSON 合并依赖 `python3` 或 `jq`。两者都没有时：配置文件不存在就直接新建；已存在就跳过该 CLI，并提示原因。
  - 重复安装是幂等的：通过命令路径识别自己写的条目，不会重复追加。
- **卸载**：
  - 只删除命令为 `itermux-notify` 的条目。
  - 删除 `~/.grok/hooks/itermux.json`，以及 `config.toml` 里我们写的那行 `notify`。
  - 删除 `~/.itermux/bin/itermux-notify`。
- **结果输出**：按 CLI 分行显示“已接入 / 跳过（原因）”。Codex 一行额外提示：“在 Codex 中执行一次 `/hooks` 并信任 itermux，才能收到确认提醒”。

## 5. App 部分

| 文件 | 改动 |
|---|---|
| `ssh-core/.../HostPlugins.kt` | 新增 `HostPlugin.AGENT_NOTIFY("notify")`，加入检测、安装、卸载脚本，复用现有 `/bin/sh -s` 的 stdin 执行方式 |
| `app/.../ui/HostPluginsSheet.kt` | 新增“AI 通知”一项：安装、卸载按钮，按 CLI 显示结果，以及 Codex 信任提示 |
| `app/.../session/AgentEventWatcher.kt`（新文件） | 在会话的 SSH 连接上开一个 exec 通道执行 `tail -n 50 -F ~/.itermux/events.jsonl`，逐行解析，丢弃 `ts <= lastSeenTs` 的旧事件；SSH 断开重连后重新打开 |
| `app/.../session/TerminalConnection.kt` | 主机装了 notify 插件时启动 watcher；**同一主机的多个会话只保留一个 watcher**（由 `SessionService` 按主机管理） |
| `app/.../session/SessionService.kt` | 新增通知渠道 `agent-events`（高优先级），负责发通知、去重和抑制；`lastSeenTs` 按主机保存在内存中 |
| `MainActivity.kt` | 处理通知点击的 `PendingIntent`（携带 sessionId），打开对应会话 |
| 设置页 | “AI 通知”总开关，以及“需要处理”“任务完成”两个子开关 |
| `AppLocale` | 新增文案的中英文 |

### 5.1 会话匹配

- 事件里的 `tmux` 与 `TerminalConnection.remoteSessionName` 相同，就归到那个会话。
- 匹配不到时（普通 SSH，或用户自己开的 tmux），归到该主机最近活跃的会话，标题只写主机名。

### 5.2 通知规则

- 标题：`主机名 · 会话标题`。正文举例：
  - “Claude 等待你的确认”
  - “Codex 任务完成：<msg>”
- 用户正在看的那个会话、且 App 在前台时，不发通知。
- 同一会话、同一类事件 10 秒内只通知一次；新的“需要处理”通知会替换该会话旧的“完成”通知（复用同一个通知 ID）。
- 通知设为 `VISIBILITY_PRIVATE`，锁屏上不显示 `msg`，和现有的隐私策略一致。
- 事件内容只保留在内存里，不写日志、不持久化。

## 6. 限制

- **只有连接在线时能收到**：依赖前台服务和 SSH 连接。App 被系统杀掉或连接断开期间的事件，重连后最多补发最近 50 行里尚未处理过的。补发时只通知 10 分钟以内的事件，避免一次弹出很多过期通知。
- **Codex 首次需要信任**：用户不执行 `/hooks` 信任的话，只能收到“完成”通知，收不到“需要确认”。
- **Codex 没有空闲事件**：没有类似 `idle_prompt` 的事件，等待用户输入时不会提醒（任务完成本身就意味着在等输入）。
- **以后可以扩展的方向**：如果要在 App 离线时也能推送，可以考虑 ntfy 或 FCM，不在本期范围内。
- **兜底（可选）**：没有 hook 的其他 CLI，可以利用已有的 `onBell()` 回调——后台会话收到 BEL 时发一条“需要关注”的通知。

## 7. 测试

- **JVM 单元测试**：
  - 事件行解析：字段缺失、非法 JSON、超长 `msg`。
  - 会话匹配。
  - 去重和抑制规则。
- **脚本测试**：在临时 `HOME` 下运行安装和卸载脚本，覆盖以下情况：
  - 配置文件不存在、为空、已有其他 hook、重复安装、已存在用户自己的 `notify`、没有 `python3`。
  - 卸载后配置与安装前一致（不考虑格式差异）。
- **真机测试**：组合 mosh+tmux、普通 SSH × Claude、Codex、Grok，各触发一次权限确认和一次完成，检查：
  - 通知内容是否正确；
  - 点击后是否跳到正确的会话；
  - 前台不重复通知；
  - 锁屏后重连能补发。

## 8. 里程碑

1. **M1 远端**：`itermux-notify` 脚本，三个 CLI 的安装、卸载、检测，脚本测试。
2. **M2 App**：`AgentEventWatcher`、通知渠道、点击跳转、设置开关，单元测试。
3. **M3 收尾**：真机验证、README 说明，以及可选的 BEL 兜底。

## 9. 待确认

1. 通知正文里是否显示 `msg` 摘要？默认显示，锁屏隐藏。
2. `idle` 事件（Claude、Grok 的 `idle_prompt`，等待输入约 60 秒后触发）是否默认通知？它和 `Stop` 的含义有重叠，建议默认关闭，在设置里可以打开。脚本照常写入事件，由 App 决定是否弹出。
3. 没有 `python3`、`jq` 又已经存在配置文件的主机，是直接跳过，还是允许用户确认后覆盖？
