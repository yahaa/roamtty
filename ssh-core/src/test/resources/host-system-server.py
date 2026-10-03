"""Loopback metadata fixture; never executes client commands. Optional fixed port for Android QA."""
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
events = sys.argv[2]
key = paramiko.RSAKey.generate(2048)


def record(value):
    with open(events, "a", encoding="utf-8") as stream:
        stream.write(value + "\n")


class Server(paramiko.ServerInterface):
    def get_allowed_auths(self, username):
        return "password"

    def check_auth_password(self, username, password):
        return paramiko.AUTH_SUCCESSFUL if password == "fixture-only" else paramiko.AUTH_FAILED

    def check_channel_request(self, kind, chanid):
        return paramiko.OPEN_SUCCEEDED

    def check_channel_pty_request(self, *args):
        record("pty")
        return True

    def check_channel_window_change_request(self, *args):
        return True

    def check_channel_shell_request(self, channel):
        record("shell")

        def shell():
            time.sleep(0.05)
            try:
                channel.sendall(b"READY\r\n")
                while True:
                    data = channel.recv(1024)
                    if not data:
                        break
                    channel.sendall(data)
            except Exception:
                pass

        threading.Thread(target=shell, daemon=True).start()
        return True

    def check_channel_exec_request(self, channel, command):
        record("exec")

        def execute():
            time.sleep(0.05)
            try:
                result = b""
                status = 0
                if mode == "slow":
                    time.sleep(30)
                elif mode == "denied":
                    status = 1
                elif mode == "oversized":
                    result = b"x" * 9000
                elif mode == "windows":
                    if command == b"cmd.exe /c ver":
                        result = b"\r\nMicrosoft Windows [Version 10.0.20348.2402]\r\n"
                    else:
                        status = 1
                elif b"__ITERMUX_KERNEL__" in command:
                    kernel = "Darwin" if mode == "macos" else "Linux"
                    distro = "custom" if mode == "linux" else mode
                    result = ("Login banner\n__ITERMUX_KERNEL__\n" + kernel +
                              "\n__ITERMUX_OS_RELEASE__\nID=" + distro +
                              "\nID_LIKE=debian\n__ITERMUX_LEGACY_RELEASE__\n").encode()
                else:
                    status = 127
                if result:
                    channel.sendall(result)
                channel.send_exit_status(status)
                channel.close()
            except Exception:
                pass

        threading.Thread(target=execute, daemon=True).start()
        return True


def serve(client):
    transport = paramiko.Transport(client)
    transport.add_server_key(key)
    try:
        transport.start_server(server=Server())
        while transport.is_active():
            time.sleep(0.02)
    except Exception:
        pass
    finally:
        transport.close()


listener = socket.socket()
listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
listener.bind(("127.0.0.1", int(sys.argv[3]) if len(sys.argv) > 3 else 0))
listener.listen(8)
print(listener.getsockname()[1], flush=True)
while True:
    client, _ = listener.accept()
    threading.Thread(target=serve, args=(client,), daemon=True).start()
