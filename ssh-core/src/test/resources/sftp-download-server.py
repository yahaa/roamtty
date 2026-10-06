"""Loopback SFTP fixture with independent stat and stream lengths; no filesystem writes."""
import io
import logging
import socket
import stat
import sys
import threading
import time

try:
    import paramiko
except ImportError:
    print("SKIP: Python paramiko is not installed", flush=True)
    sys.exit(0)

logging.disable(logging.CRITICAL)
key = paramiko.RSAKey.generate(2048)
files = {
    "/normal": (stat.S_IFREG, 32, b"x" * 32),
    "/short": (stat.S_IFREG, 100, b"x" * 32),
    "/grown": (stat.S_IFREG, 16, b"x" * 32),
    "/empty": (stat.S_IFREG, 0, b""),
    "/dynamic": (stat.S_IFREG, 0, b"x" * 32),
    "/special": (stat.S_IFIFO, 100, b"x" * 32),
    "/unknown": (stat.S_IFREG, None, b"x" * 32),
}


class Sftp(paramiko.SFTPServerInterface):
    def stat(self, path):
        if path not in files:
            return paramiko.SFTP_NO_SUCH_FILE
        mode, size, _ = files[path]
        attributes = paramiko.SFTPAttributes()
        attributes.st_mode = mode | 0o600
        if size is not None:
            attributes.st_size = size
        return attributes

    def open(self, path, flags, attributes):
        if path not in files:
            return paramiko.SFTP_NO_SUCH_FILE
        handle = paramiko.SFTPHandle(flags)
        handle.readfile = io.BytesIO(files[path][2])
        return handle


class Server(paramiko.ServerInterface):
    def get_allowed_auths(self, username):
        return "password"

    def check_auth_password(self, username, password):
        return paramiko.AUTH_SUCCESSFUL if password == "fixture-only" else paramiko.AUTH_FAILED

    def check_channel_request(self, kind, chanid):
        return paramiko.OPEN_SUCCEEDED if kind == "session" else paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED

    def check_channel_pty_request(self, *args):
        return True

    def check_channel_window_change_request(self, *args):
        return True

    def check_channel_shell_request(self, channel):
        def shell():
            try:
                channel.sendall(b"READY\r\n")
                while channel.recv(1024):
                    pass
            except (EOFError, OSError):
                pass
        threading.Thread(target=shell, daemon=True).start()
        return True


def serve(client):
    transport = paramiko.Transport(client)
    transport.add_server_key(key)
    transport.set_subsystem_handler("sftp", paramiko.SFTPServer, Sftp)
    try:
        transport.start_server(server=Server())
        while transport.is_active():
            time.sleep(0.02)
    finally:
        transport.close()


listener = socket.socket()
listener.bind(("127.0.0.1", int(sys.argv[1]) if len(sys.argv) > 1 else 0))
listener.listen(8)
print(listener.getsockname()[1], flush=True)
while True:
    client, _ = listener.accept()
    threading.Thread(target=serve, args=(client,), daemon=True).start()
