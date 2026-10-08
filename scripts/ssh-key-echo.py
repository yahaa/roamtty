"""Local SSH fixture for TerminalExtraKeysTest; requires paramiko 3.5.1.

Run with `PYTHONPATH=.local/py-deps python3 scripts/ssh-key-echo.py` after
installing paramiko into `.local/py-deps`, then `adb reverse tcp:22228 tcp:22228`.
"""

import socket
import threading
import os
from pathlib import Path

import paramiko


HOST_KEY = paramiko.RSAKey.generate(2048)
SFTP_ROOT = Path(__file__).resolve().parent.parent / ".local" / "sftp-fixture"
SFTP_ROOT.mkdir(parents=True, exist_ok=True)
for name in ("phone-a", "phone-b"):
    (SFTP_ROOT / name).mkdir(exist_ok=True)
for name in ("assets", "src", "dist", "dist-backup"):
    (SFTP_ROOT / "phone-a" / name).mkdir(exist_ok=True)
for path, content in {
    ".bash_history": "git status\npwd\nls -lah\n",
    "phone-a/design-notes.md": "# Design notes\n",
    "phone-a/package.json": '{"name":"design-studio"}\n',
    "phone-a/README.md": "# Demo project\n",
    "phone-a/dist/iTermux-debug.apk": "APK search fixture\n",
    "phone-a/dist-backup/iTermux-debug.apk": "APK search fixture backup\n",
}.items():
    target = SFTP_ROOT / path
    if not target.exists():
        target.write_text(content)


class FixtureSftp(paramiko.SFTPServerInterface):
    def local_path(self, path):
        candidate = (SFTP_ROOT / path.lstrip("/")).resolve()
        if not candidate.is_relative_to(SFTP_ROOT):
            raise PermissionError(path)
        return candidate

    def stat(self, path):
        try:
            return paramiko.SFTPAttributes.from_stat(self.local_path(path).stat())
        except OSError as error:
            return paramiko.SFTPServer.convert_errno(error.errno)

    def lstat(self, path):
        try:
            return paramiko.SFTPAttributes.from_stat(self.local_path(path).lstat())
        except OSError as error:
            return paramiko.SFTPServer.convert_errno(error.errno)

    def list_folder(self, path):
        try:
            return [paramiko.SFTPAttributes.from_stat(entry.stat(), entry.name)
                    for entry in self.local_path(path).iterdir()]
        except OSError as error:
            return paramiko.SFTPServer.convert_errno(error.errno)

    def open(self, path, flags, attr):
        try:
            descriptor = os.open(self.local_path(path), flags, 0o600)
            handle = paramiko.SFTPHandle(flags)
            mode = "r+b" if flags & os.O_RDWR else "wb" if flags & os.O_WRONLY else "rb"
            handle.readfile = handle.writefile = os.fdopen(descriptor, mode)
            return handle
        except OSError as error:
            return paramiko.SFTPServer.convert_errno(error.errno)

    def remove(self, path):
        try:
            self.local_path(path).unlink()
            return paramiko.SFTP_OK
        except OSError as error:
            return paramiko.SFTPServer.convert_errno(error.errno)

    def rename(self, oldpath, newpath):
        try:
            self.local_path(oldpath).rename(self.local_path(newpath))
            return paramiko.SFTP_OK
        except OSError as error:
            return paramiko.SFTPServer.convert_errno(error.errno)


class TestServer(paramiko.ServerInterface):
    def __init__(self):
        self.shell_ready = threading.Event()

    def get_allowed_auths(self, username):
        return "password"

    def check_auth_password(self, username, password):
        if username == "itermux_test" and password == "test-only":
            return paramiko.AUTH_SUCCESSFUL
        return paramiko.AUTH_FAILED

    def check_channel_request(self, kind, chanid):
        return paramiko.OPEN_SUCCEEDED if kind == "session" else paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED

    def check_channel_pty_request(self, *args):
        return True

    def check_channel_window_change_request(self, *args):
        return True

    def check_channel_shell_request(self, channel):
        self.shell_ready.set()
        return True


def serve(client):
    transport = paramiko.Transport(client)
    transport.add_server_key(HOST_KEY)
    transport.set_subsystem_handler("sftp", paramiko.SFTPServer, FixtureSftp)
    server = TestServer()
    try:
        transport.start_server(server=server)
        channel = transport.accept(10)
        if channel is None or not server.shell_ready.wait(10):
            return
        channel.sendall(b"\r\nKEY_ECHO_READY\r\n")
        while data := channel.recv(4096):
            for byte in data:
                if byte == 0x1E:
                    channel.sendall(b"\r\n\x1b[32mHISTORY_BEGIN\x1b[0m\r\n")
                    for row in range(1, 181):
                        channel.sendall(f"HISTORY_{row:03d} ".encode("ascii") + "终端字体测试".encode("utf-8") + b"\r\n")
                else:
                    channel.sendall(f"\r\nKEY_{byte:02X}\r\n".encode("ascii"))
                    # Leave a typed `/` at the cursor, like a prompt starting a slash command.
                    if byte == 0x2F:
                        channel.sendall(b"/")
    except (EOFError, OSError, paramiko.SSHException):
        pass
    finally:
        transport.close()


listener = socket.socket()
listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
listener.bind(("127.0.0.1", 22228))
listener.listen(4)
print("KEY_ECHO_LISTENING", flush=True)
try:
    while True:
        client, _ = listener.accept()
        threading.Thread(target=serve, args=(client,), daemon=True).start()
except KeyboardInterrupt:
    pass
finally:
    listener.close()
