#!/usr/bin/env python3
"""Isolated Linux PTY fixture for native history and repeated image insertion tests."""
import ctypes
import os
import re
import signal
import sys
import termios
import tty
import time

name = sys.argv[1] if len(sys.argv) > 1 else "codex"
ctypes.CDLL(None).prctl(15, name.encode(), 0, 0, 0)
saved = termios.tcgetattr(0)
images = []
position = 0
history = False
submitted = 0
navigation = 0
buffer = b""


def render(*_):
    rows = os.get_terminal_size().lines
    banner = "Codex fixture" if not images else "Image attachment fixture"
    output = f"\x1b[H\x1b[2J{banner}\r\n"
    if history:
        output += f"CLI_HISTORY_{position:03d}\r\n"
        output += "\r\n".join(f"ARCHIVE_{n:03d}" for n in range(240 - position, 240 - position + max(1, rows - 7)))
    else:
        output += "CLI_LIVE\r\n"
    output += f"\x1b[{rows - 2};1HIMAGES={len(images)} SUBMITTED={submitted} NAVKEYS={navigation}\r\n"
    output += " ".join(f"[Image #{i + 1}]" for i in range(len(images)))
    sys.stdout.write(output)
    sys.stdout.flush()


try:
    tty.setraw(0)
    sys.stdout.write("\x1b[?1049h\x1b[?2004h")
    if name != "codex":
        sys.stdout.write("\x1b[?1000h\x1b[?1006h")
    signal.signal(signal.SIGWINCH, render)
    # Show output, then erase it as real full-screen tools do. No native history support.
    for n in range(60):
        sys.stdout.write(f"\x1b[H\x1b[2JOUTPUT_{n:03d}\r\nThis frame will be overwritten.\r\n")
        sys.stdout.flush()
        time.sleep(0.06)
    render()
    while True:
        buffer += os.read(0, 4096)
        while buffer:
            if buffer.startswith(b"\x1b[200~"):
                if b"\x1b[201~" not in buffer:
                    break
                text, buffer = buffer[6:].split(b"\x1b[201~", 1)
                for path in text.decode().split():
                    if re.fullmatch(r"/tmp/itermux-img-[a-f0-9-]+\.png", path):
                        with open(path, "rb") as image:
                            if image.read(8) == b"\x89PNG\r\n\x1a\n":
                                images.append(path)
                render()
                continue
            match = re.match(rb"\x1b\[<(64|65);\d+;\d+M", buffer)
            if match:
                position = max(0, min(220, position + (3 if match[1] == b"64" else -3)))
                buffer = buffer[match.end():]
                render()
                continue
            key = next((key for key in (b"\x1b[5~", b"\x1b[6~", b"\x14", b"\x03", b"\r", b"\n") if buffer.startswith(key)), None)
            if key is None:
                if buffer == b"\x1b":
                    navigation += 1
                    history = False
                    position = 0
                    buffer = b""
                    render()
                    continue
                if buffer.startswith(b"\x1b") and len(buffer) < 6:
                    break
                buffer = buffer[1:]
                continue
            buffer = buffer[len(key):]
            if key == b"\x03":
                raise KeyboardInterrupt
            if key == b"\x14":
                navigation += 1
            elif key in (b"\r", b"\n"):
                submitted += 1
            else:
                navigation += 1
            render()
except (KeyboardInterrupt, EOFError):
    pass
finally:
    sys.stdout.write("\x1b[?2004l\x1b[?1000l\x1b[?1006l\x1b[?1049l")
    sys.stdout.flush()
    termios.tcsetattr(0, termios.TCSADRAIN, saved)
