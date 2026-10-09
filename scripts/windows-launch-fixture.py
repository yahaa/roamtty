"""Loopback SSH fixture for WindowsLaunchSheetTest (requires paramiko and ROAMTTY_PWSH).

Rejects POSIX execs like native Windows OpenSSH, executes real PowerShell against
an isolated PSDrive, and returns native Windows paths. It is not a Windows OS test.
Usage: PYTHONPATH=.local/py-deps ROAMTTY_PWSH=... python3 scripts/windows-launch-fixture.py
"""
import base64
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time

import paramiko

key = paramiko.RSAKey.generate(2048)
root = tempfile.TemporaryDirectory(prefix="roamtty-windows-")
home = Path(root.name) / "Users" / "测试"
(home / "My projects" / "中文 [work]").mkdir(parents=True)
(home / "Documents").mkdir()
pwsh = os.environ["ROAMTTY_PWSH"]


def ps_literal(value):
    encoded = base64.b64encode(value.encode()).decode()
    return f"([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('{encoded}')))"


class Server(paramiko.ServerInterface):
    def __init__(self):
        self.username = ""
        self.listings = 0

    def get_allowed_auths(self, username):
        return "password"

    def check_auth_password(self, username, password):
        self.username = username
        return paramiko.AUTH_SUCCESSFUL if password == "fixture-only" else paramiko.AUTH_FAILED

    def check_channel_request(self, kind, chanid):
        return paramiko.OPEN_SUCCEEDED if kind == "session" else paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED

    def check_channel_exec_request(self, channel, command):
        def run():
            try:
                text = command.decode()
                if not text.startswith("powershell.exe ") or "-EncodedCommand " not in text:
                    channel.sendall(b"The system cannot find the path specified.\r\n")
                    channel.send_exit_status(1)
                    return
                self.listings += 1
                if self.username == "retry" and self.listings == 1:
                    channel.send_exit_status(3)
                    return
                if self.username == "failed":
                    channel.send_exit_status(3)
                    return
                script = base64.b64decode(text.split("-EncodedCommand ", 1)[1]).decode("utf-16le")
                setup = f"New-PSDrive -Name C -PSProvider FileSystem -Root {ps_literal(root.name)} | Out-Null; "
                setup += "Set-Variable -Name HOME -Value 'C:/Users/测试' -Force; "
                encoded = base64.b64encode((setup + script).encode("utf-16le")).decode()
                result = subprocess.run([pwsh, "-NoLogo", "-NoProfile", "-NonInteractive", "-OutputFormat", "Text", "-EncodedCommand", encoded],
                                        capture_output=True, timeout=15)
                lines = result.stdout.decode("utf-8-sig").splitlines()
                # PSDrive provider paths use the host OS: translate the protocol's path headers only.
                for index in range(min(2, len(lines))):
                    if lines[index].startswith(root.name):
                        lines[index] = "C:" + lines[index][len(root.name):]
                    lines[index] = lines[index].replace("/", "\\")
                if lines:
                    channel.sendall(("\r\n".join(lines) + "\r\n").encode())
                channel.send_exit_status(result.returncode)
            finally:
                channel.close()
        threading.Thread(target=run, daemon=True).start()
        return True


def serve(client):
    transport = paramiko.Transport(client)
    transport.add_server_key(key)
    try:
        transport.start_server(server=Server())
        channels = []
        while transport.is_active():
            channel = transport.accept(0.2)
            if channel is not None:
                channels.append(channel)
            channels = [item for item in channels if not item.closed]
    finally:
        transport.close()


listener = socket.socket()
listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
listener.bind(("127.0.0.1", 22248))
listener.listen(8)
print("WINDOWS_LAUNCH_READY 22248", flush=True)
try:
    while True:
        client, _ = listener.accept()
        threading.Thread(target=serve, args=(client,), daemon=True).start()
finally:
    listener.close()
    root.cleanup()
