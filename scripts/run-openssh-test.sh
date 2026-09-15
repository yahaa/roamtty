#!/usr/bin/env bash
# Keep WSL attached while Android tests use the loopback server.
set -euo pipefail
bash "$(dirname "$0")/prepare-openssh-test.sh"
while [ -f /tmp/itermux-ssh-test/sshd.pid ] && kill -0 "$(cat /tmp/itermux-ssh-test/sshd.pid)" 2>/dev/null; do
  sleep 2
done
