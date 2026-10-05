#!/bin/sh
# Roamtty AI notify hook, installed by the Roamtty app ("AI 交互提醒" plugin).
#
#   roamtty-notify attention [codex]   a CLI waits for a permission or an answer
#   roamtty-notify resolved [codex]    the CLI moved on: withdraw the reminder
#   roamtty-notify --trust-codex       trust these hooks in Codex (what /hooks does)
#
# Hook runs append one JSON line to ~/.roamtty/events.jsonl, which the app follows over SSH.
# A hook must never slow down or block its CLI: it prints nothing and always exits 0.

D="$HOME/.roamtty"
E="$D/events.jsonl"
P="$D/pending"

trust_codex() {
  command -v codex >/dev/null 2>&1 && command -v python3 >/dev/null 2>&1 || { [ -n "$ROAMTTY_REPORT" ] && echo __ITERMUX_CODEX_TRUST__=unavailable; return 0; }
  # Codex's own app-server lists the hooks with their trust hashes and records the trust,
  # exactly as /hooks does. Only handlers that run this script are trusted.
  python3 - <<'PY' 2>/dev/null
import json, os, subprocess, threading
report = bool(os.environ.get("ROAMTTY_REPORT"))
def done(status, count=0):
    if report:
        print("__ITERMUX_CODEX_TRUST__=" + status)
    elif count:
        print("Roamtty: trusted %d Codex hook(s) for AI reminders" % count)
try:
    p = subprocess.Popen(["codex", "app-server"], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                         stderr=subprocess.DEVNULL, universal_newlines=True)
except Exception:
    done("unavailable"); raise SystemExit(0)
timer = threading.Timer(15, p.kill); timer.daemon = True; timer.start()
def send(message):
    p.stdin.write(json.dumps(message) + "\n"); p.stdin.flush()
def reply(request_id):
    for line in p.stdout:
        try:
            message = json.loads(line)
        except ValueError:
            continue
        if message.get("id") == request_id:
            return message
    raise EOFError
try:
    send({"method": "initialize", "id": 0, "params": {"clientInfo": {"name": "roamtty", "title": "Roamtty", "version": "1"}}})
    reply(0)
    send({"method": "initialized"})
    send({"method": "hooks/list", "id": 1, "params": {"cwds": [os.getcwd()]}})
    hooks = [hook for entry in reply(1).get("result", {}).get("data", []) for hook in entry.get("hooks", [])]
    ours = [hook for hook in hooks if "/.roamtty/bin/roamtty-notify" in (hook.get("command") or "")]
    todo = {hook["key"]: {"trusted_hash": hook["currentHash"]} for hook in ours
            if hook.get("trustStatus") in ("untrusted", "modified") and hook.get("key") and hook.get("currentHash")}
    if todo:
        send({"method": "config/batchWrite", "id": 2, "params": {
            "edits": [{"keyPath": "hooks.state", "value": todo, "mergeStrategy": "upsert"}], "reloadUserConfig": True}})
        if "error" in reply(2):
            raise EOFError
    done("trusted" if todo else ("ok" if ours else "missing"), len(todo))
except Exception:
    done("failed")
finally:
    try:
        p.stdin.close()
    except Exception:
        pass
    p.kill()
PY
  return 0
}

if [ "$1" = --trust-codex ]; then trust_codex; exit 0; fi

kind=$1
agent=$2
[ -n "$GROK_SESSION_ID" ] && agent=grok
[ -n "$agent" ] || agent=claude

# Every tool call runs the resolved hook: without a pending reminder it stops right here.
if [ "$kind" = resolved ]; then
  set -- "$P"/*
  if [ ! -e "$1" ]; then cat >/dev/null 2>&1; exit 0; fi
fi

in=$(cat 2>/dev/null)
field() { printf '%s' "$in" | grep -o "\"$1\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" 2>/dev/null | head -n 1 | sed 's/.*"\([^"]*\)"$/\1/'; }
sid=$GROK_SESSION_ID
[ -n "$sid" ] || sid=$(field 'session_\{0,1\}[iI]d')

# A reminder belongs to the tmux session the CLI runs in; outside tmux, to the CLI's own session.
tm=
if [ "$agent" = codex ] && command -v tmux >/dev/null 2>&1; then
  # Codex may run hooks in its shared app-server daemon, whose environment names the tmux pane
  # that started the daemon. The pane showing this session sits in its folder, running Codex.
  cwd=$(field cwd)
  [ -n "$cwd" ] && tm=$(tmux list-panes -a -F '#{pane_current_path}	#{pane_current_command}	#{session_name}' 2>/dev/null \
    | awk -F '\t' -v d="$cwd" '$1 == d && $2 ~ /codex|node/ { print $3; exit }')
fi
if [ -z "$tm" ] && [ -n "$TMUX" ]; then
  if [ -n "$TMUX_PANE" ]; then tm=$(tmux display-message -p -t "$TMUX_PANE" '#S' 2>/dev/null)
  else tm=$(tmux display-message -p '#S' 2>/dev/null); fi
fi
key=${tm:-$sid}
key=$(printf '%s' "${key:-default}" | tr -c 'A-Za-z0-9._-' '_' | cut -c1-80)

case "$kind" in
  resolved) [ -e "$P/$key" ] || exit 0; rm -f "$P/$key" ;;
  attention) mkdir -p "$P" 2>/dev/null; : > "$P/$key" 2>/dev/null ;;
  *) exit 0 ;;
esac

esc() { printf '%s' "$1" | tr -d '\000-\037' | sed 's/\\/\\\\/g; s/"/\\"/g'; }
ts=$(date +%s)
id="$ts-$$"
line=
if [ "$kind" = attention ] && command -v python3 >/dev/null 2>&1; then
  line=$(printf '%s' "$in" | ROAMTTY_ID="$id" ROAMTTY_AGENT="$agent" ROAMTTY_TMUX="$tm" ROAMTTY_KEY="$key" python3 -c '
import json, os, sys, time
try:
    data = json.loads(sys.stdin.read() or "{}")
except ValueError:
    data = {}
if not isinstance(data, dict):
    data = {}
def get(*keys):
    for key in keys:
        if data.get(key):
            return data[key]
    return None
def question(value):
    if isinstance(value, dict):
        for key in ("question", "prompt", "header", "message"):
            if isinstance(value.get(key), str) and value[key].strip():
                return value[key]
        value = list(value.values())
    if isinstance(value, list):
        for item in value:
            found = question(item)
            if found:
                return found
    return None
event = str(get("hook_event_name", "hookEventName") or "").replace("_", "").lower()
kind = str(get("notification_type", "notificationType") or "")
tool_input = get("tool_input", "toolInput") or {}
if event == "pretooluse":
    reason, msg = "question", question(tool_input) or ""
elif kind or event == "notification":
    reason = "permission" if kind == "permission_prompt" else "question"
    msg = str(get("message") or "")
else:
    reason, msg = "permission", ""
    if isinstance(tool_input, dict):
        msg = tool_input.get("description") or tool_input.get("command") or tool_input.get("cmd") or ""
    if isinstance(msg, list):
        msg = " ".join(str(part) for part in msg)
    msg = str(msg) or str(get("tool_name", "toolName") or "")
msg = " ".join(msg.split())
print(json.dumps({"v": 1, "id": os.environ["ROAMTTY_ID"], "ts": int(time.time()), "agent": os.environ["ROAMTTY_AGENT"],
    "kind": "attention", "reason": reason, "tmux": os.environ["ROAMTTY_TMUX"], "key": os.environ["ROAMTTY_KEY"],
    "cwd": str(get("cwd") or os.getcwd()), "msg": msg[:200]}, ensure_ascii=False))
' 2>/dev/null)
fi
if [ -z "$line" ]; then
  extra=
  if [ "$kind" = attention ]; then
    reason=permission
    case "$in" in
      *'"permission_prompt"'*) ;;
      *'"elicitation'*|*'"agent_needs_input"'*|*'"AskUserQuestion"'*|*'"ask_user_question"'*) reason=question ;;
    esac
    # Already JSON-escaped text, copied as is; a cut escape at the end is dropped.
    msg=
    for field in message question description command; do
      msg=$(printf '%s' "$in" | tr -d '\n' | grep -oE "\"$field\"[[:space:]]*:[[:space:]]*\"([^\"\\\\]|\\\\.)*\"" 2>/dev/null \
        | head -n 1 | sed 's/^"[a-z]*"[[:space:]]*:[[:space:]]*"//; s/"$//' | cut -c1-200 | sed 's/\\u[0-9a-fA-F]\{0,3\}$//; s/\\$//')
      [ -n "$msg" ] && break
    done
    extra=",\"reason\":\"$reason\",\"msg\":\"$msg\""
  fi
  line="{\"v\":1,\"id\":\"$id\",\"ts\":$ts,\"agent\":\"$agent\",\"kind\":\"$kind\",\"tmux\":\"$(esc "$tm")\",\"key\":\"$key\",\"cwd\":\"$(esc "$PWD")\"$extra}"
fi

mkdir -p "$D" 2>/dev/null
printf '%s\n' "$line" >> "$E" 2>/dev/null
size=$(wc -c < "$E" 2>/dev/null | tr -d ' ')
if [ "${size:-0}" -gt 262144 ] 2>/dev/null; then
  tail -n 200 "$E" > "$E.$$" 2>/dev/null && mv -f "$E.$$" "$E" 2>/dev/null
fi
exit 0
