#!/usr/bin/env python3
"""Full-screen chat program fixture: fixed header, scrolling transcript, spinner and input box,
repainted with absolute cursor moves on the alternate screen (as Claude Code, Codex and Grok do).
Answers stream word by word; frames are written in small pieces so readers see partial frames.
Usage: chat-tui-fixture.py [sync] — "sync" wraps frames in DEC 2026 synchronized output."""
import os
import sys
import time

sync = len(sys.argv) > 1 and sys.argv[1] == "sync"
spinner = "|/-\\"
answer = [f"ANSWER_{n:03d} the quick brown fox jumps" for n in range(80)]


def frame(lines, tick, status):
    rows = os.get_terminal_size().lines
    body = rows - 5
    visible = lines[-body:]
    out = ["\x1b[?2026h" if sync else "", "\x1b[?25l", "\x1b[1;1H\x1b[2KChat fixture header"]
    for row in range(body):
        text = visible[row] if row < len(visible) else ""
        out.append(f"\x1b[{row + 2};1H\x1b[2K{text}")
    out.append(f"\x1b[{rows - 3};1H\x1b[2K{spinner[tick % 4]} {status} ({tick}s)")
    out.append(f"\x1b[{rows - 2};1H\x1b[2K> type a message")
    out.append(f"\x1b[{rows - 1};1H\x1b[2Kmodel · context 98% left")
    out.append("\x1b[?2026l" if sync else "")
    data = "".join(out)
    # Several writes per frame, like a renderer flushing as it goes.
    for start in range(0, len(data), 200):
        sys.stdout.write(data[start:start + 200])
        sys.stdout.flush()


sys.stdout.write("\x1b[?1049h\x1b[?1000h\x1b[?1006h")
lines = ["> please list eighty lines", ""]
tick = 0
for line in answer:
    words = line.split(" ")
    lines.append("")
    for count in range(1, len(words) + 1):
        lines[-1] = " ".join(words[:count])
        tick += 1
        frame(lines, tick, "Working")
        time.sleep(0.004)
lines.append("")
frame(lines, tick, "Done")
sys.stdout.write("\x1b[?25h")
sys.stdout.flush()
time.sleep(3600)
