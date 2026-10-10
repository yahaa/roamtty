#!/bin/sh
# Roamtty: records what an AI coding agent is doing as options of its tmux session, which the app reads.
# Usage: agent-hook.sh <agent> <event>, with the agent's hook JSON on stdin. Never fails the agent.
[ -n "$TMUX_PANE" ] || exit 0
PATH="$PATH:/opt/homebrew/bin:/usr/local/bin:/opt/local/bin:/home/linuxbrew/.linuxbrew/bin:/snap/bin"
agent=$1
event=$2
input=$(cat 2>/dev/null)

# The first string value of a JSON key, cleaned for one line: escapes undone, "|" and control characters gone, shortened.
field() {
  printf '%s' "$input" | sed -nE "s/.*\"$1\"[[:space:]]*:[[:space:]]*\"(([^\"\\\\]|\\\\.)*)\".*/\1/p" | head -n 1 |
    sed -E 's/\\[nrt]/ /g; s/\\u[0-9a-fA-F]{4}//g; s/\\(["\\/])/\1/g' | tr '|' '/' | tr -d '\000-\037' | cut -c1-"${2:-160}"
}

get() { tmux show-options -qv -t "$TMUX_PANE" "$1" 2>/dev/null; }
put() { tmux set-option -q -t "$TMUX_PANE" "$1" "$2" 2>/dev/null; }

# state|since|agent|tool|detail; "since" keeps the time the state began while it repeats.
record() {
  now=$(date +%s)
  case "$(get @roamtty_agent)" in "$1|"*) now=$(get @roamtty_agent | cut -d '|' -f 2) ;; esac
  put @roamtty_agent "$1|$now|$agent|$2|$3"
}

case "$event" in
  start) record idle "" "" ;;
  prompt)
    put @roamtty_task "$(field prompt 200)"
    record working "" "" ;;
  tool)
    tool=$(field tool_name 40)
    detail=$(field command 120)
    [ -n "$detail" ] || detail=$(field file_path 120)
    [ -n "$detail" ] || detail=$(field pattern 120)
    [ -n "$detail" ] || detail=$(field url 120)
    [ -n "$detail" ] || detail=$(field description 120)
    record working "$tool" "$detail" ;;
  post) record working "" "" ;;
  ask)
    tool=$(field tool_name 40)
    detail=$(field message 120)
    [ -n "$detail" ] || detail=$(field command 120)
    record waiting "$tool" "$detail" ;;
  notify)
    # Claude Code also notifies when idle for a minute; only a request for permission matters here.
    case "$(field message 200)" in *ermission*|*pprov*) record waiting "" "$(field message 120)" ;; esac ;;
  stop) record idle "" "" ;;
  end)
    tmux set-option -u -q -t "$TMUX_PANE" @roamtty_agent 2>/dev/null
    tmux set-option -u -q -t "$TMUX_PANE" @roamtty_task 2>/dev/null ;;
esac
exit 0
