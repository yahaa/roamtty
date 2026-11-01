"""Loopback SSH fixture for ReattachIntegrationTest; requires Python 3 and paramiko.

No shell commands are executed. Each process owns an ephemeral port and host keys.
"""
import logging
import socket
import sys
import threading
import time

try:
    import paramiko
except ImportError:
    print("SKIP: Python paramiko is not installed", flush=True)
    sys.exit(0)

logging.disable(logging.CRITICAL)
mode = sys.argv[1]
key = paramiko.RSAKey.generate(2048)
replacement_key = paramiko.RSAKey.generate(2048)
outage = threading.Event()
frozen = threading.Event()


class Server(paramiko.ServerInterface):
    def __init__(self, transport, attempt):
        self.transport = transport
        self.attempt = attempt

    def get_allowed_auths(self, username):
        return "password"

    def check_auth_password(self, username, password):
        if mode == "auth" and self.attempt > 1:
            return paramiko.AUTH_FAILED
        return paramiko.AUTH_SUCCESSFUL

    def check_channel_request(self, kind, chanid):
        if mode == "frozen" and self.attempt == 1 and frozen.is_set():
            # A dead network: the server never answers again, yet the socket stays open.
            time.sleep(30)
        if mode in ("reject-probe", "reject-probe-delayed") and frozen.is_set():
            # Like OpenSSH with MaxSessions 1: the live PTY keeps working, extra channels are refused.
            if mode == "reject-probe-delayed":
                time.sleep(1.2)
            return paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED
        return paramiko.OPEN_SUCCEEDED

    def check_channel_pty_request(self, *args):
        return True

    def check_channel_window_change_request(self, *args):
        return True

    def check_channel_exec_request(self, channel, command):
        def execute():
            # Let the exec request acknowledgement reach the client first.
            time.sleep(0.05)
            if b"command -v tmux" in command:
                channel.send_exit_status(1 if mode == "missing-tmux" and self.attempt > 1 else 0)
                channel.close()
                return
            if not command.startswith(b"exec ") or b"tmux -u new-session -A" not in command:
                channel.send_exit_status(127)
                channel.close()
                return
            channel.sendall(b"READY:" + command + b"\r\n")
            channel.recv(1024)
            if mode in ("frozen", "reject-probe", "reject-probe-delayed") and self.attempt == 1:
                frozen.set()
                return
            if mode.startswith("exit"):
                if mode == "exit-delayed":
                    channel.shutdown_write()
                    time.sleep(0.2)
                if mode != "exit-no-status":
                    channel.send_exit_status(1 if mode == "exit-error" else 0)
                channel.close()
            else:
                if mode == "refused" and self.attempt == 1:
                    outage.set()
                self.transport.close()

        threading.Thread(target=execute, daemon=True).start()
        return True


def listen(port):
    listener = socket.socket()
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind(("127.0.0.1", port))
    listener.listen(8)
    listener.settimeout(0.05)
    return listener


def serve(client, attempt):
    if mode == "timeout" and attempt == 2:
        time.sleep(2)
        client.close()
        return
    if mode == "handshake-eof" and attempt == 2:
        client.close()
        return
    transport = paramiko.Transport(client)
    transport.add_server_key(replacement_key if mode == "host-key" and attempt > 1 else key)
    try:
        transport.start_server(server=Server(transport, attempt))
        while transport.is_active():
            time.sleep(0.05)
    except Exception:
        pass
    finally:
        transport.close()


listener = listen(0)
port = listener.getsockname()[1]
print(port, flush=True)
attempt = 0
while True:
    if outage.is_set():
        listener.close()
        time.sleep(2)
        listener = listen(port)
        outage.clear()
    try:
        client, _ = listener.accept()
    except socket.timeout:
        continue
    attempt += 1
    threading.Thread(target=serve, args=(client, attempt), daemon=True).start()
