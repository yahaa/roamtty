"""Isolated real tmux check; no user server/session is touched."""
import fcntl
import os
import pty
import shutil
import struct
import subprocess
import sys
import tempfile
import termios
import time

if not shutil.which("tmux"):
    print("SKIP: tmux not installed")
    sys.exit(0)

clients = []
fds = []
with tempfile.TemporaryDirectory(prefix="roamtty-attach-") as root:
    env = dict(os.environ, TMUX_TMPDIR=root, TERM="xterm-256color")
    env.pop("TMUX", None)

    def tmux(*args):
        return subprocess.check_output(["tmux", *args], env=env, text=True).strip()

    def attach(command):
        master, slave = pty.openpty()
        fds.extend((master, slave))
        fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", 24, 80, 0, 0))
        client = subprocess.Popen(["/bin/sh", "-c", command], env=env, stdin=slave, stdout=slave, stderr=slave)
        clients.append(client)
        return client

    def wait_for(check):
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            if check():
                return
            time.sleep(0.02)
        raise AssertionError("tmux condition timed out")

    try:
        tmux("new-session", "-d", "-s", "unrelated")
        other_pane = tmux("display-message", "-p", "-t", "unrelated", "#{pane_pid}")
        if sys.argv[1] == "restore":
            tmux("new-session", "-d", "-s", "roamtty-review")
            old = attach("exec tmux new-session -A -s roamtty-review")
            wait_for(lambda: len(tmux("list-clients", "-t", "roamtty-review", "-F", "#{client_pid}").splitlines()) == 1)
            pane = tmux("display-message", "-p", "-t", "roamtty-review", "#{pane_pid}")
            tmux("copy-mode", "-t", "roamtty-review")
            assert tmux("display-message", "-p", "-t", "roamtty-review", "#{pane_in_mode}") == "1"
        restored = attach(sys.argv[2])
        wait_for(lambda: subprocess.run(["tmux", "has-session", "-t", "roamtty-review"],
            env=env, capture_output=True).returncode == 0)
        wait_for(lambda: len(tmux("list-clients", "-t", "roamtty-review", "-F", "#{client_pid}").splitlines()) == 1)
        wait_for(lambda: tmux("display-message", "-p", "-t", "roamtty-review", "#{pane_in_mode}") == "0")
        assert restored.poll() is None
        # Swipes scroll tmux history only through mouse mode, set for the app's own session.
        wait_for(lambda: tmux("show-options", "-v", "-t", "roamtty-review", "mouse") == "on")
        assert tmux("show-options", "-gv", "mouse") == "off"
        # The status line is off for the app's session only, so the pane fills the 24-row terminal.
        wait_for(lambda: tmux("show-options", "-v", "-t", "roamtty-review", "status") == "off")
        assert tmux("show-options", "-gv", "status") == "on"
        assert tmux("show-options", "-v", "-t", "unrelated", "status") == ""
        wait_for(lambda: tmux("display-message", "-p", "-t", "roamtty-review", "#{pane_height}") == "24")
        if sys.argv[1] == "new":
            # The first prompt sits on the pane's last row, next to the keyboard.
            wait_for(lambda: tmux("display-message", "-p", "-t", "roamtty-review", "#{cursor_y}") == "23")
        if sys.argv[1] == "restore":
            old.wait(timeout=2)
            assert pane == tmux("display-message", "-p", "-t", "roamtty-review", "#{pane_pid}")
        assert other_pane == tmux("display-message", "-p", "-t", "unrelated", "#{pane_pid}")
        print("PASS")
    finally:
        subprocess.run(["tmux", "kill-server"], env=env, capture_output=True)
        for client in clients:
            if client.poll() is None:
                client.kill()
            client.wait(timeout=2)
        for fd in fds:
            os.close(fd)
