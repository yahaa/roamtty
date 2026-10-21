#!/usr/bin/env bash
# Loopback-only OpenSSH + SFTP fixture for the end-to-end suite (scripts/e2e/run.sh).
# Usage: scripts/e2e/fixture.sh start|stop|status. Needs sudo; never touches the system sshd_config.
#
# A dedicated account (roamtty_e2e) with its own home under /tmp keeps the suite away from real users,
# their tmux servers and the other fixtures. Its password is random and lives in .private/e2e-password.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PORT="${ROAMTTY_E2E_PORT:-22258}"
USER_NAME=roamtty_e2e
FIXTURE=/tmp/roamtty-e2e # also spelled out literally in the rm commands below
HOME_DIR="$FIXTURE/home"
PASSWORD_FILE="$ROOT/.private/e2e-password"
# The remote desktop runs on an Xvfb display of the fixture account; the plugin comes from a local
# release directory (scripts/package.sh in roamtty-desktop), served on loopback as its download URL.
DESKTOP_DISPLAY="${ROAMTTY_E2E_DISPLAY:-77}"
DESKTOP_DIST="${ROAMTTY_DESKTOP_DIST:-$ROOT/../roamtty-desktop/dist}"
DESKTOP_PORT="${ROAMTTY_E2E_DESKTOP_PORT:-8797}"
KEYS="$ROOT/ssh-core/src/test/resources/keys"

running() { sudo test -f "$FIXTURE/sshd.pid" && sudo kill -0 "$(sudo cat "$FIXTURE/sshd.pid")" 2>/dev/null; }

seed_home() {
  # Files the suite browses, previews, renames and downloads. Rebuilt on every start, so runs start equal.
  local work="$HOME_DIR/e2e"
  sudo rm -rf /tmp/roamtty-e2e/home/e2e
  sudo mkdir -p "$work/docs" "$work/src" "$work/big" "$work/upload"
  sudo tee "$work/README.md" >/dev/null <<'EOF'
# E2E Fixture

Roamtty end-to-end **preview** check.

- item one
- item two

```kotlin
fun main() = println("hello")
```
EOF
  sudo tee "$work/src/Main.kt" >/dev/null <<'EOF'
package e2e

/** Syntax highlighting sample. */
fun main(args: Array<String>) {
    val greeting = "E2E_CODE_PREVIEW"
    for (i in 0 until 3) println("$greeting $i")
}
EOF
  printf 'E2E_RENAME_ME\n' | sudo tee "$work/notes.txt" >/dev/null
  printf 'E2E_DELETE_ME\n' | sudo tee "$work/delete-me.txt" >/dev/null
  printf 'E2E_DOWNLOAD_PAYLOAD %s\n' "$(date +%s)" | sudo tee "$work/download.txt" >/dev/null
  # A valid 32x32 PNG, written without image tools.
  python3 -c '
import struct, sys, zlib
w = h = 32
raw = b"".join(b"\0" + b"".join(bytes((x * 8, y * 8, 160)) for x in range(w)) for y in range(h))
chunk = lambda t, d: struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
sys.stdout.buffer.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
    + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))' | sudo tee "$work/pixel.png" >/dev/null
  # Directory-listing and large-preview performance inputs.
  sudo bash -c "cd '$work/big' && for i in \$(seq -w 1 1500); do : > file-\$i.txt; done"
  sudo bash -c "seq 1 60000 | sed 's/^/line /' > '$work/docs/large.log'"
  # SFTP throughput input.
  sudo mkdir -p "$work/transfer"
  sudo head -c 16777216 /dev/urandom | sudo tee "$work/transfer/blob.bin" >/dev/null
  # Shell history feeds the 常用命令 sheet.
  printf '%s\n' 'echo E2E_HISTORY_ONE' 'ls -la' 'echo E2E_HISTORY_$((40+2))' 'git status' | sudo tee "$HOME_DIR/.bash_history" >/dev/null
  sudo chown -R "$USER_NAME:$USER_NAME" "$HOME_DIR"
}

start() {
  if ! id "$USER_NAME" >/dev/null 2>&1; then
    sudo mkdir -p "$HOME_DIR"
    sudo useradd --no-create-home --home-dir "$HOME_DIR" --shell /bin/bash "$USER_NAME"
    sudo touch "$FIXTURE/created-user"
  elif [ "$(getent passwd "$USER_NAME" | cut -d: -f6)" != "$HOME_DIR" ]; then
    echo "Refusing to reuse $USER_NAME: its home is not $HOME_DIR." >&2; exit 1
  fi
  sudo mkdir -p "$HOME_DIR" /run/sshd
  mkdir -p "$ROOT/.private"
  [ -s "$PASSWORD_FILE" ] || openssl rand -hex 20 > "$PASSWORD_FILE"
  printf '%s:%s\n' "$USER_NAME" "$(cat "$PASSWORD_FILE")" | sudo chpasswd
  # The same public test keys as the other fixtures; their private halves ship in androidTest assets.
  cat "$KEYS"/*.pub | sudo tee "$FIXTURE/authorized_keys" >/dev/null
  sudo chmod 644 "$FIXTURE/authorized_keys"
  [ -f "$FIXTURE/host_ed25519" ] || sudo ssh-keygen -q -t ed25519 -N '' -f "$FIXTURE/host_ed25519"
  sudo tee "$FIXTURE/sshd_config" >/dev/null <<EOF
Port $PORT
ListenAddress 127.0.0.1
HostKey $FIXTURE/host_ed25519
PidFile $FIXTURE/sshd.pid
AuthorizedKeysFile $FIXTURE/authorized_keys
StrictModes no
PasswordAuthentication yes
KbdInteractiveAuthentication yes
UsePAM yes
PermitRootLogin no
AllowUsers $USER_NAME
AllowTcpForwarding no
X11Forwarding no
PermitTunnel no
PrintMotd no
PrintLastLog no
MaxStartups 50:30:100
MaxSessions 30
Subsystem sftp internal-sftp
SetEnv PATH=$FIXTURE/bin
LogLevel ERROR
EOF
  # mosh needs UDP, which adb reverse cannot carry, so the fixture hides mosh-server and terminals use SSH.
  # Every other system command stays reachable through a PATH of links.
  sudo rm -rf /tmp/roamtty-e2e/bin
  sudo mkdir -p "$FIXTURE/bin"
  sudo find /usr/bin /usr/sbin -maxdepth 1 \( -type f -o -type l \) ! -name 'mosh*' -exec ln -sf {} "$FIXTURE/bin/" \; 2>/dev/null
  # Stand-ins for the AI agents: they print how they were started, then wait for a line like a prompt.
  for agent in claude codex grok; do
    sudo rm -f "/tmp/roamtty-e2e/bin/$agent"
    printf '#!/bin/sh\necho "E2E_FAKE_AGENT %s $*"\necho "cwd=$(pwd)"\nread -r line\necho "agent got: $line"\n' "$agent" \
      | sudo tee "$FIXTURE/bin/$agent" >/dev/null
    sudo chmod 755 "$FIXTURE/bin/$agent"
  done
  sudo /usr/sbin/sshd -t -f "$FIXTURE/sshd_config"
  seed_home
  start_desktop
  if running; then sudo kill -HUP "$(sudo cat "$FIXTURE/sshd.pid")"
  else sudo /usr/sbin/sshd -f "$FIXTURE/sshd_config" -E "$FIXTURE/sshd.log"; fi
  for _ in $(seq 1 20); do ss -ltn | grep -q "127.0.0.1:$PORT " && break; sleep 0.2; done
  ss -ltn | grep -q "127.0.0.1:$PORT " || { echo "sshd did not listen on $PORT" >&2; exit 1; }
  echo "E2E fixture ready: $USER_NAME@127.0.0.1:$PORT (tmux, SFTP; mosh hidden)"
}

start_desktop() {
  # Every run installs the plugin from scratch, as a first use does.
  sudo rm -rf /tmp/roamtty-e2e/home/.roamtty
  if command -v Xvfb >/dev/null && command -v xterm >/dev/null; then
    sudo -u "$USER_NAME" env HOME="$HOME_DIR" setsid Xvfb ":$DESKTOP_DISPLAY" -screen 0 1280x800x24 -nolisten tcp >/dev/null 2>&1 < /dev/null &
    for _ in $(seq 1 25); do [ -S "/tmp/.X11-unix/X$DESKTOP_DISPLAY" ] && break; sleep 0.2; done
    sudo -u "$USER_NAME" env HOME="$HOME_DIR" DISPLAY=":$DESKTOP_DISPLAY" setsid xterm -geometry 100x30+0+0 -fa Monospace -fs 14 \
      -e bash --noprofile --norc >/dev/null 2>&1 < /dev/null &
    echo "E2E desktop: Xvfb :$DESKTOP_DISPLAY with xterm"
  else
    echo "E2E desktop skipped: install Xvfb and xterm" >&2
  fi
  if [ -f "$DESKTOP_DIST/manifest.json" ]; then
    (cd "$DESKTOP_DIST" && setsid python3 -m http.server "$DESKTOP_PORT" --bind 127.0.0.1 >/dev/null 2>&1 < /dev/null &)
    echo "E2E desktop plugin: http://127.0.0.1:$DESKTOP_PORT/ from $DESKTOP_DIST"
  fi
}

stop() {
  pkill -f "http.server $DESKTOP_PORT --bind 127.0.0.1" 2>/dev/null || true
  if running; then sudo kill "$(sudo cat "$FIXTURE/sshd.pid")"; fi
  # Only the fixture account's own processes (its tmux server and shells).
  if id "$USER_NAME" >/dev/null 2>&1; then sudo pkill -u "$USER_NAME" 2>/dev/null || true; fi
  echo "E2E fixture stopped."
}

case "${1:-start}" in
  start) start ;;
  stop) stop ;;
  status) running && echo "running on $PORT" || { echo stopped; exit 1; } ;;
  *) echo "Usage: $0 start|stop|status" >&2; exit 2 ;;
esac
