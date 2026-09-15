#!/usr/bin/env bash
# Dedicated loopback-only OpenSSH fixture. Run under WSL as root. Does not change system sshd_config.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FIXTURE=/tmp/itermux-ssh-test
mkdir -p "$FIXTURE" "$ROOT/.private" "$ROOT/app/src/androidTest/assets" "$ROOT/ssh-core/src/test/resources/keys"
if id itermux_test >/dev/null 2>&1 && [ ! -f "$FIXTURE/created-user" ] && [ ! -f "$ROOT/.private/fixture-user-created" ]; then
  echo 'Refusing to modify an existing account not created by this test fixture.' >&2
  exit 1
fi
if id itermux_test >/dev/null 2>&1 && [ "$(getent passwd itermux_test | cut -d: -f6)" != "$FIXTURE/home" ]; then
  echo 'Refusing to modify an account with a different home directory.' >&2
  exit 1
fi
if ! id itermux_test >/dev/null 2>&1; then
  useradd --no-create-home --home-dir "$FIXTURE/home" --shell /bin/bash itermux_test
  touch "$FIXTURE/created-user"
fi
touch "$FIXTURE/created-user" "$ROOT/.private/fixture-user-created"
mkdir -p "$FIXTURE/home"
chown itermux_test:itermux_test "$FIXTURE/home"
if [ ! -f "$ROOT/.private/test-password" ]; then openssl rand -hex 20 > "$ROOT/.private/test-password"; fi
printf 'itermux_test:%s\n' "$(cat "$ROOT/.private/test-password")" | chpasswd
for type in ed25519 rsa; do
  key="$ROOT/ssh-core/src/test/resources/keys/$type"
  if [ ! -f "$key" ]; then ssh-keygen -q -t "$type" -N itermux-test-passphrase -f "$key"; fi
  cp "$key" "$ROOT/app/src/androidTest/assets/test_$type"
done
cat "$ROOT/ssh-core/src/test/resources/keys/"*.pub > "$FIXTURE/home/authorized_keys"
chown itermux_test:itermux_test "$FIXTURE/home/authorized_keys"
chmod 600 "$FIXTURE/home/authorized_keys"
if [ ! -f "$FIXTURE/host_ed25519" ]; then
  if [ -f "$ROOT/.private/test-host_ed25519" ]; then
    cp "$ROOT/.private/test-host_ed25519" "$FIXTURE/host_ed25519"
    cp "$ROOT/.private/test-host_ed25519.pub" "$FIXTURE/host_ed25519.pub"
    chmod 600 "$FIXTURE/host_ed25519"
  else
    ssh-keygen -q -t ed25519 -N '' -f "$FIXTURE/host_ed25519"
  fi
fi
cp "$FIXTURE/host_ed25519" "$ROOT/.private/test-host_ed25519"
cp "$FIXTURE/host_ed25519.pub" "$ROOT/.private/test-host_ed25519.pub"
mkdir -p /run/sshd
cat > "$FIXTURE/sshd_config" <<EOF
Port 22228
ListenAddress 127.0.0.1
HostKey $FIXTURE/host_ed25519
PidFile $FIXTURE/sshd.pid
AuthorizedKeysFile $FIXTURE/home/authorized_keys
StrictModes yes
PasswordAuthentication yes
KbdInteractiveAuthentication yes
UsePAM yes
PermitRootLogin no
AllowUsers itermux_test
AllowTcpForwarding no
X11Forwarding no
PermitTunnel no
PrintMotd no
PrintLastLog no
LogLevel ERROR
EOF
/usr/sbin/sshd -t -f "$FIXTURE/sshd_config"
if [ ! -f "$FIXTURE/sshd.pid" ] || ! kill -0 "$(cat "$FIXTURE/sshd.pid")" 2>/dev/null; then
  /usr/sbin/sshd -f "$FIXTURE/sshd_config" -E "$FIXTURE/sshd.log"
else
  kill -HUP "$(cat "$FIXTURE/sshd.pid")"
fi
cp "$ROOT/.private/test-password" "$ROOT/app/src/androidTest/assets/test_password"
if [ -f "$ROOT/.tools/codex-linux/codex-x86_64-unknown-linux-musl" ]; then
  install -m 755 "$ROOT/.tools/codex-linux/codex-x86_64-unknown-linux-musl" "$FIXTURE/codex"
fi
# A second isolated endpoint makes every exec probe fail, exercising missing tmux.
sed -e 's/Port 22228/Port 22229/' -e "s|PidFile .*|PidFile $FIXTURE/no-tmux.pid|" "$FIXTURE/sshd_config" > "$FIXTURE/no-tmux.conf"
echo 'ForceCommand /bin/false' >> "$FIXTURE/no-tmux.conf"
/usr/sbin/sshd -t -f "$FIXTURE/no-tmux.conf"
if [ ! -f "$FIXTURE/no-tmux.pid" ] || ! kill -0 "$(cat "$FIXTURE/no-tmux.pid")" 2>/dev/null; then
  /usr/sbin/sshd -f "$FIXTURE/no-tmux.conf" -E "$FIXTURE/sshd.log"
fi
/usr/sbin/sshd -V 2>&1 || true
tmux -V
vim --version | head -n 1
ssh-keygen -lf "$FIXTURE/host_ed25519.pub" -E sha256
echo 'Loopback OpenSSH test fixture ready on port 22228 for itermux_test.'
