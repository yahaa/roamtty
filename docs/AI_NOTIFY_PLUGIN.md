# AI 交互提醒插件方案

> 状态：待评审 · 2026-10-04

## 1. 目标与范围

Claude Code、Codex、Grok 在远端**停下来等用户操作**时，手机弹出系统通知，点击直接进入对应会话处理。

本期只做以下两类场景：

- **权限确认**：执行命令、编辑文件、访问网络等，需要用户批准。
- **向用户提问**：CLI 主动提问、让用户选择，或 MCP 服务请求输入。

**本期不做**：

- 任务完成通知。
- 空闲提醒（`idle_prompt`）：它在任何一轮结束后都会触发，含义和“完成”一样。
- 出错通知。

事件格式预留了 `kind` 字段，后续可以扩展这些场景。

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

## 3. 要接入的事件（已核对）

本机核对的版本：Claude Code 2.1.289、Codex 0.160.0、Grok 1.0.46。

### 3.1 “需要交互”事件 → `kind=attention`

| | 权限确认 | 向用户提问 |
|---|---|---|
| **Claude Code** | `Notification`，匹配 `permission_prompt` | `PreToolUse`，匹配 `AskUserQuestion`；`Notification`，匹配 `elicitation_dialog`、`elicitation_url_dialog`、`agent_needs_input` |
| **Codex** | `PermissionRequest` | 提问工具 `request_user_input` 仍是开发中功能（`default_mode_request_user_input` 未启用），**本期不接**，M1 时再确认 |
| **Grok** | `Notification`，匹配 `permission_prompt` | `PreToolUse`，匹配 `ask_user_question` |

### 3.2 “交互已处理”事件 → `kind=resolved`

用户在终端里处理完之后，App 要撤销对应的通知，避免通知栏留下过期提醒。

- **用哪些事件判断“已处理”**：
  - Claude 和 Grok：`PostToolUse`、`UserPromptSubmit`、`Stop`。
  - Codex：`PostToolUse`、`Stop`。
- **怎样保证不变慢**：只有当前会话存在“待处理”标记文件 `~/.itermux/pending/<key>` 时，脚本才写 `resolved` 并删除标记；没有标记就立即退出。所以每次工具调用的额外开销只是检查一次文件是否存在。

### 3.3 生效条件

| CLI | 怎样生效 |
|---|---|
| Claude Code | 写入 `~/.claude/settings.json` 后，新启动的会话生效 |
| Grok | 写入全局 `~/.grok/hooks/itermux.json`，默认就信任；它也会读 Claude 的配置，相同命令会去重 |
| Codex | 写入 `~/.codex/hooks.json` 后，用户**必须在 Codex 中执行一次 `/hooks` 信任**，否则 hook 会被跳过，收不到任何提醒 |

参考：本机文档 `~/.grok/docs/user-guide/10-hooks.md`、[Codex Hooks](https://learn.chatgpt.com/docs/hooks)、[Claude Code Hooks](https://code.claude.com/docs/en/hooks)。

## 4. 远端部分

### 4.1 事件格式（每行一个 JSON）

```json
{"v":1,"ts":1791100000,"agent":"claude","kind":"attention","reason":"permission","tmux":"itermux-ab12","cwd":"/home/u/proj","msg":"Claude needs your permission to use Bash"}
```

- `agent`：`claude`、`codex` 或 `grok`。
- `kind`：`attention` 或 `resolved`。
- `reason`：`permission` 或 `question`，只在 `attention` 时出现。
- `tmux`：CLI 运行在 tmux 里时，用 `tmux display -p '#S'` 取到的会话名；不在 tmux 里时为空。
- `msg`：截断到 200 字符。来源：Claude 和 Grok 用 `Notification` 的 `message`；Codex 用 `tool_input.description`；提问类用问题文本。

### 4.2 `itermux-notify` 脚本

- 用 POSIX `sh` 编写，**无论什么情况都 `exit 0`**，耗时不超过 1 秒，不影响 CLI 本身。
- 从 stdin 读取 hook JSON。
- 判断来源：有 `GROK_SESSION_ID` 就是 Grok；否则 stdin 里有 `PermissionRequest` 就是 Codex；其余都算 Claude。
- 待处理标记的 key：有 tmux 时用 tmux 会话名，否则用 CLI 的 `session_id`。
- 解析 JSON：有 `python3` 就用它，没有就用 `sed` 提取需要的几个字段。
- 事件文件超过 256 KiB 时只保留最后 200 行，原子替换。

### 4.3 写入的配置（`$HOME` 在安装时展开为绝对路径，下面记作 `$N`）

**Claude**：合并进 `~/.claude/settings.json`。

```json
{"hooks":{
  "Notification":[{"matcher":"permission_prompt|elicitation_dialog|elicitation_url_dialog|agent_needs_input","hooks":[{"type":"command","command":"$N","timeout":5}]}],
  "PreToolUse":[{"matcher":"AskUserQuestion","hooks":[{"type":"command","command":"$N","timeout":5}]}],
  "PostToolUse":[{"hooks":[{"type":"command","command":"$N","timeout":5}]}],
  "UserPromptSubmit":[{"hooks":[{"type":"command","command":"$N","timeout":5}]}],
  "Stop":[{"hooks":[{"type":"command","command":"$N","timeout":5}]}]
}}
```

**Grok**：写入独立文件 `~/.grok/hooks/itermux.json`。

- 结构同上。`PreToolUse` 的匹配写成 `AskUserQuestion|ask_user_question`；`Notification` 的匹配只写 `permission_prompt`。
- **命令字符串必须和 Claude 的完全一致**：Grok 会对相同的 handler 去重，这样不会因为同时读了 Claude 配置而触发两次。

**Codex**：合并进 `~/.codex/hooks.json`。

- `PermissionRequest`：产生 `attention`。
- `PostToolUse`、`Stop`：产生 `resolved`。
- 不修改 `config.toml`。

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
  - 删除 `~/.grok/hooks/itermux.json`。
  - 删除整个 `~/.itermux/bin/` 和 `~/.itermux/pending/`。
- **结果输出**：按 CLI 分行显示“已接入 / 跳过（原因）”。Codex 一行**醒目提示**：“请在 Codex 中执行一次 `/hooks` 并信任 itermux，否则收不到提醒”。

## 5. App 部分

| 文件 | 改动 |
|---|---|
| `ssh-core/.../HostPlugins.kt` | 新增 `HostPlugin.AGENT_NOTIFY("notify")`，加入检测、安装、卸载脚本，复用现有 `/bin/sh -s` 的 stdin 执行方式 |
| `app/.../ui/HostPluginsSheet.kt` | 新增“AI 交互提醒”一项：安装、卸载按钮，按 CLI 显示结果，以及 Codex 信任提示 |
| `app/.../session/AgentEventWatcher.kt`（新文件） | 在会话的 SSH 连接上开一个 exec 通道执行 `tail -n 50 -F ~/.itermux/events.jsonl`，逐行解析，丢弃 `ts <= lastSeenTs` 的旧事件；SSH 断开重连后重新打开 |
| `app/.../session/SessionService.kt` | 按主机管理 watcher（**同一主机只保留一个**）；新增通知渠道 `agent-attention`（高优先级，带声音和振动）；负责发通知、撤销、抑制；`lastSeenTs` 按主机保存在内存中 |
| `app/.../session/TerminalConnection.kt` | 连接成功且主机装了 notify 插件时，向 `SessionService` 登记该主机 |
| `MainActivity.kt` | 处理通知点击的 `PendingIntent`（携带 sessionId），打开对应会话 |
| 设置页 | “AI 交互提醒”开关 |
| `AppLocale` | 新增文案的中英文 |

### 5.1 会话匹配

- 事件里的 `tmux` 与 `TerminalConnection.remoteSessionName` 相同，就归到那个会话。
- 匹配不到时（普通 SSH，或用户自己开的 tmux），归到该主机最近活跃的会话，标题只写主机名。

### 5.2 通知规则

- 标题：`主机名 · 会话标题`。正文举例：
  - “Claude 请求权限：<msg>”
  - “Grok 在等你回答：<msg>”
- 每个会话**只有一条**提醒通知，用固定的通知 ID：
  - 新的 `attention` 会替换旧的那条。
  - 收到 `resolved`，或用户点开了这个会话，就撤销这条通知。
- 用户正在看的那个会话、且 App 在前台时，不发通知。
- 同一会话 5 秒内只通知一次，防止连续请求权限时反复振动。
- 通知设为 `VISIBILITY_PRIVATE`，锁屏上不显示 `msg`，和现有的隐私策略一致。
- 事件内容只保留在内存里，不写日志、不持久化。

## 6. 限制

- **只有连接在线时能收到**：依赖前台服务和 SSH 连接。重连后从最近 50 行里补发，只补 10 分钟以内、并且还没有对应 `resolved` 的 `attention`。
- **Codex 必须先信任**：用户没执行 `/hooks` 信任前，Codex 的任何提醒都收不到。
- **Codex 的“误报”需要实测**：`PermissionRequest` 会在审批流程开始前触发；如果审批被自动批准（`guardian_approval` 或已保存的授权），可能产生一条很快又被 `resolved` 撤销的通知。M1 实测，必要时给 `attention` 加 1–2 秒延迟再弹出。
- **Codex 提问暂不支持**：等官方功能稳定后再接。

## 7. 测试

- **JVM 单元测试**：
  - 事件行解析：字段缺失、非法 JSON、超长 `msg`。
  - 会话匹配。
  - `attention` 与 `resolved` 的配对、撤销、5 秒节流、前台抑制。
- **脚本测试**：在临时 `HOME` 下运行安装和卸载脚本，覆盖以下情况：
  - 配置文件不存在、为空、已有其他 hook、重复安装、没有 `python3`。
  - 卸载后配置与安装前一致（不考虑格式差异）。
  - 没有待处理标记时，`PostToolUse` 不写事件。
- **真机测试**：组合 mosh+tmux、普通 SSH × Claude、Codex、Grok，检查：
  - 权限请求时弹出通知，在终端里批准后通知自动消失；
  - Claude 和 Grok 提问时弹出通知；
  - 点击通知跳到正确的会话；
  - App 在前台查看该会话时不弹通知；
  - 锁屏后重连能补发。

## 8. 里程碑

1. **M1 远端**：`itermux-notify` 脚本，三个 CLI 的安装、卸载、检测，脚本测试；实测 Codex `PermissionRequest` 的误报情况。
2. **M2 App**：`AgentEventWatcher`、通知渠道、撤销逻辑、点击跳转、设置开关，单元测试。
3. **M3 收尾**：真机验证、README 说明。

**后续**：任务完成通知、空闲提醒、出错通知，以及 Codex 提问。

## 9. 待确认

1. 通知正文里是否显示 `msg`（例如要执行的命令）？默认显示，锁屏隐藏。
2. 没有 `python3`、`jq` 又已经存在配置文件的主机，是直接跳过，还是允许用户确认后覆盖？
