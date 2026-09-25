"""Local SSH fixture for TerminalExtraKeysTest; requires paramiko 3.5.1.

Run with `PYTHONPATH=.local/py-deps python3 scripts/ssh-key-echo.py` after
installing paramiko into `.local/py-deps`, then `adb reverse tcp:22228 tcp:22228`.
"""

import socket
import threading

import paramiko


HOST_KEY = paramiko.RSAKey.generate(2048)


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
