#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FIXTURE=/tmp/itermux-ssh-test
if [ -f "$FIXTURE/sshd.pid" ]; then kill "$(cat "$FIXTURE/sshd.pid")" 2>/dev/null || true; fi
if [ -f "$FIXTURE/no-tmux.pid" ]; then kill "$(cat "$FIXTURE/no-tmux.pid")" 2>/dev/null || true; fi
if id itermux_test >/dev/null 2>&1 && { [ -f "$FIXTURE/created-user" ] || [ -f "$ROOT/.private/fixture-user-created" ]; }; then
  pkill -u itermux_test || true
  userdel itermux_test
fi
rm -f "$ROOT/.private/fixture-user-created" "$ROOT/.private/test-password" "$ROOT/app/src/androidTest/assets/test_password"
# Deliberately retain fixture files for debugging. No recursive deletion of host directories.
echo 'Temporary SSH server stopped and temporary account removed if created by this task.'
