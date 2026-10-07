#!/usr/bin/env python3
"""Draw the launcher icon: the toolbox ghost (ToolboxPet.kt, at rest) on the terminal background.

Writes the adaptive icon layers and the splash icon as vector drawables, and optionally an HTML preview:
  python3 scripts/render-launcher-icon.py [docs/previews/logo-redesign.html]
"""
import math
import pathlib
import sys

RES = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/res/drawable"

TERMINAL_BG = "#252728"   # TerminalColorScheme default background
TERMINAL_FG = "#F3F0EA"
ACCENT = "#EFA783"        # cursor colour and colorScheme.primary
GHOST_BODY = "#F8F7FF"
GHOST_INK = "#3B3550"
GHOST_BLUSH = "#FF9EB5"
WRENCH_METAL = "#D9DEE8"
KEYBOARD_BODY = "#EDEBF5"

# The pet's 26-unit grid, scaled into the 108-unit adaptive icon and centred in its 66-unit safe circle.
GRID_SCALE = 1.75
GRID_CENTER = (13.5, 14.0)
ICON_CENTER = (54.0, 50.0)


def f(v):
    return f"{v:.3f}".rstrip("0").rstrip(".")


def ellipse(cx, cy, rx, ry):
    return (f"M{f(cx - rx)},{f(cy)}a{f(rx)},{f(ry)} 0,1 0,{f(2 * rx)},0"
            f"a{f(rx)},{f(ry)} 0,1 0,{f(-2 * rx)},0Z")


def circle(cx, cy, r):
    return ellipse(cx, cy, r, r)


def round_rect(x, y, w, h, r):
    return (f"M{f(x + r)},{f(y)}H{f(x + w - r)}a{f(r)},{f(r)} 0,0 1,{f(r)},{f(r)}V{f(y + h - r)}"
            f"a{f(r)},{f(r)} 0,0 1,{f(-r)},{f(r)}H{f(x + r)}a{f(r)},{f(r)} 0,0 1,{f(-r)},{f(-r)}"
            f"V{f(y + r)}a{f(r)},{f(r)} 0,0 1,{f(r)},{f(-r)}Z")


def line(x1, y1, x2, y2):
    return f"M{f(x1)},{f(y1)}L{f(x2)},{f(y2)}"


def arc(cx, cy, r, start, sweep):
    a, b = math.radians(start), math.radians(start + sweep)
    return (f"M{f(cx + r * math.cos(a))},{f(cy + r * math.sin(a))}"
            f"A{f(r)},{f(r)} 0,{1 if sweep > 180 else 0} 1,{f(cx + r * math.cos(b))},{f(cy + r * math.sin(b))}")


def ghost_body():
    d = "M4,20.4L4,11C4,6.2 7.6,3 12,3C16.4,3 20,6.2 20,11L20,20.4"
    steps = 48
    for i in range(1, steps + 1):
        t = i / steps
        d += f"L{f(20 - 16 * t)},{f(20.4 + 1.3 * math.sin(t * 6 * math.pi))}"
    return d + "Z"


class Path:
    def __init__(self, d, fill=None, stroke=None, width=0, alpha=1.0, cap="round", even_odd=False, name=None):
        self.d, self.fill, self.stroke, self.width, self.alpha, self.cap, self.even_odd = d, fill, stroke, width, alpha, cap, even_odd
        self.name = name

    def svg(self):
        a = f'<path d="{self.d}"'
        a += f' fill="{self.fill}"' if self.fill else ' fill="none"'
        if self.fill and self.alpha != 1:
            a += f' fill-opacity="{self.alpha}"'
        if self.even_odd:
            a += ' fill-rule="evenodd"'
        if self.stroke:
            a += f' stroke="{self.stroke}" stroke-width="{f(self.width)}" stroke-linecap="{self.cap}" stroke-linejoin="round"'
            if self.alpha != 1:
                a += f' stroke-opacity="{self.alpha}"'
        return a + "/>"

    def vector(self, indent):
        a = f'{indent}<path' + (f' android:name="{self.name}"' if self.name else "") + f' android:pathData="{self.d}"'
        if self.fill:
            a += f'\n{indent}    android:fillColor="{self.fill}"'
            if self.alpha != 1:
                a += f' android:fillAlpha="{self.alpha}"'
        if self.even_odd:
            a += f'\n{indent}    android:fillType="evenOdd"'
        if self.stroke:
            a += (f'\n{indent}    android:strokeColor="{self.stroke}" android:strokeWidth="{f(self.width)}"'
                  f'\n{indent}    android:strokeLineCap="{self.cap}" android:strokeLineJoin="round"')
            if self.alpha != 1:
                a += f' android:strokeAlpha="{self.alpha}"'
        return a + " />"


class Group:
    def __init__(self, children, scale=1.0, translate=(0.0, 0.0), rotate=0.0, pivot=(0.0, 0.0), name=None):
        self.children, self.scale, self.translate, self.rotate, self.pivot = children, scale, translate, rotate, pivot
        self.name = name

    def svg(self):
        t = []
        if self.translate != (0.0, 0.0):
            t.append(f"translate({f(self.translate[0])} {f(self.translate[1])})")
        if self.scale != 1:
            t.append(f"scale({f(self.scale)})")
        if self.rotate:
            t.append(f"rotate({f(self.rotate)} {f(self.pivot[0])} {f(self.pivot[1])})")
        return f'<g transform="{" ".join(t)}">' + "".join(c.svg() for c in self.children) + "</g>"

    def vector(self, indent):
        a = f"{indent}<group" + (f' android:name="{self.name}"' if self.name else "")
        if self.translate != (0.0, 0.0):
            a += f' android:translateX="{f(self.translate[0])}" android:translateY="{f(self.translate[1])}"'
        if self.scale != 1:
            a += f' android:scaleX="{f(self.scale)}" android:scaleY="{f(self.scale)}"'
        if self.rotate:
            a += f' android:rotation="{f(self.rotate)}"'
        if self.rotate or self.name and self.pivot != (0.0, 0.0):
            a += f' android:pivotX="{f(self.pivot[0])}" android:pivotY="{f(self.pivot[1])}"'
        return a + ">\n" + "\n".join(c.vector(indent + "    ") for c in self.children) + f"\n{indent}</group>"


def on_grid(children, offset=0.0):
    # Android applies a group's scale before its translation, as SVG's "translate scale" does.
    return Group(children, scale=GRID_SCALE, translate=(ICON_CENTER[0] - GRID_CENTER[0] * GRID_SCALE + offset,
                                                        ICON_CENTER[1] - GRID_CENTER[1] * GRID_SCALE + offset))


def ghost():
    paw_left, paw_right = (3.9, 14.6), (19.6, 14.2)
    board = (paw_left[0] - 2.2, paw_left[1] + 0.2)
    keys = [Path(round_rect(board[0] + 0.8 + col * 1.45, board[1] + 0.75 + row * 1.25, 0.95, 0.8, 0.25), fill=ACCENT)
            for row in range(2) for col in range(4)]
    keys.append(Path(round_rect(board[0] + 1.9, board[1] + 3.25, 3.2, 0.6, 0.3), fill=ACCENT))
    eyes = []
    for x in (9.2, 14.8):
        eyes += [Path(ellipse(x, 11.2, 1.1, 1.6), fill=GHOST_INK), Path(circle(x + 0.35, 10.4, 0.45), fill="#FFFFFF")]
    return [
        # The shadow sits on the ground under the hem.
        Path(ellipse(12, 24.4, 5, 0.8), fill="#000000", alpha=0.35),
        Group([
            Group([
                Path(line(*paw_right, 22.4, 7.6), stroke=GHOST_INK, width=2.8),
                Path(line(*paw_right, 22.4, 7.6), stroke=ACCENT, width=1.4),
                Path(arc(23.2, 5.4, 2.1, -22, 270), stroke=GHOST_INK, width=3),
                Path(arc(23.2, 5.4, 2.1, -22, 270), stroke=WRENCH_METAL, width=1.5),
            ], pivot=paw_right, name="wrench"),
            Path(ghost_body(), fill=GHOST_BODY),
            Path(ghost_body(), stroke=GHOST_INK, width=1.3),
            Group([
                Path(round_rect(*board, 7.2, 4.4, 0.9), fill=KEYBOARD_BODY),
                Path(round_rect(*board, 7.2, 4.4, 0.9), stroke=GHOST_INK, width=1.1),
                *keys,
            ], rotate=-10, pivot=paw_left),
            Path(circle(*paw_left, 1.7), fill=GHOST_BODY),
            Path(circle(*paw_left, 1.7), stroke=GHOST_INK, width=1.3),
            Path(circle(*paw_right, 1.8), fill=GHOST_BODY),
            Path(circle(*paw_right, 1.8), stroke=GHOST_INK, width=1.3),
            Group(eyes, pivot=(12.0, 11.2), name="eyes"),
            Path(ellipse(7.6, 13.65, 1.2, 0.65), fill=GHOST_BLUSH, alpha=0.6),
            Path(ellipse(16.4, 13.65, 1.2, 0.65), fill=GHOST_BLUSH, alpha=0.6),
            Path(ellipse(12, 14.5, 0.8, 0.9), fill=GHOST_INK),
        ], translate=(0.0, 0.4), name="ghost"),
    ]


def terminal(background="M0,0H108V108H0Z", offset=0.0):
    # Dim scrollback above, and the live prompt with its block cursor under the ghost.
    dim = [Path(line(x1 + offset, y + offset, x2 + offset, y + offset), stroke=TERMINAL_FG, width=2.4, alpha=0.1)
           for x1, x2, y in [(30, 44, 27), (25, 37, 34), (70, 80, 34), (24, 32, 41)]]
    return [
        Path(background, fill=TERMINAL_BG),
        *dim,
        Path(f"M{f(46 + offset)},{f(76 + offset)}L{f(51 + offset)},{f(80.5 + offset)} {f(46 + offset)},{f(85 + offset)}",
             stroke=ACCENT, width=2.6),
        Path(round_rect(55 + offset, 75.5 + offset, 6, 10, 1), fill=ACCENT, alpha=0.85, name="cursor"),
    ]


def monochrome():
    eyes = "".join(ellipse(x, 11.2, 1.1, 1.6) for x in (9.2, 14.8)) + ellipse(12, 14.5, 0.8, 0.9)
    return [
        on_grid([Group([
            Path(line(19.6, 14.2, 22.4, 7.6), stroke="#FFFFFF", width=2.4),
            Path(arc(23.2, 5.4, 2.1, -22, 270), stroke="#FFFFFF", width=2.2),
            Path(ghost_body() + eyes, fill="#FFFFFF", even_odd=True),
        ], translate=(0.0, 0.4))]),
        Path("M46,76L51,80.5 46,85", stroke="#FFFFFF", width=2.6),
        Path(round_rect(55, 75.5, 6, 10, 1), fill="#FFFFFF"),
    ]


def splash():
    # The launcher icon's visible 72 units as a rounded tile, about 80dp once the system fits 432dp into 288dp.
    tile = round_rect(0, 0, 72, 72, 19)
    return [Group(terminal(tile, -18) + [on_grid(ghost(), -18)], scale=5 / 3, translate=(156.0, 156.0))]


def vector(children, comment, side=108):
    body = "\n".join(c.vector("    ") for c in children)
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            f"<!-- {comment} Generated by scripts/render-launcher-icon.py; edit that instead. -->\n"
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    android:width="{side}dp" android:height="{side}dp"\n'
            f'    android:viewportWidth="{side}" android:viewportHeight="{side}">\n'
            f"{body}\n</vector>\n")


def main():
    fg = [on_grid(ghost())]
    (RES / "ic_launcher_terminal.xml").write_text(vector(terminal(), "Launcher background: the terminal the ghost lives in."))
    (RES / "ic_launcher_ghost.xml").write_text(vector(fg, "Launcher foreground: the toolbox ghost at rest."))
    (RES / "ic_launcher_monochrome.xml").write_text(vector(monochrome(), "Themed launcher icon."))
    (RES / "ic_startup.xml").write_text(vector(splash(), "Splash icon; startup_animated.xml animates its named parts.", 432))
    if len(sys.argv) > 1:
        layers = "".join(c.svg() for c in terminal() + fg)
        mono = "".join(c.svg() for c in monochrome()).replace("#FFFFFF", "#442315")

        def icon(clip, content, size):
            return (f'<svg width="{size}" height="{size}" viewBox="18 18 72 72"><defs><clipPath id="c{size}{len(clip)}">'
                    f'{clip}</clipPath></defs><g clip-path="url(#c{size}{len(clip)})">{content}</g></svg>')
        square = '<rect x="18" y="18" width="72" height="72" rx="16"/>'
        round_ = '<circle cx="54" cy="54" r="36"/>'
        themed = '<rect width="108" height="108" fill="#F4DED3"/>' + mono
        rows = "".join(f"<div>{icon(square, layers, n)}{icon(round_, layers, n)}{icon(round_, themed, n)}</div>"
                       for n in (240, 96, 48))
        pathlib.Path(sys.argv[1]).write_text(
            '<!doctype html><html lang="zh-CN"><meta charset="utf-8">'
            '<meta name="viewport" content="width=device-width, initial-scale=1"><title>Roamtty · Logo</title>'
            '<style>body{margin:0;padding:32px 16px;background:#1b1d1e;color:#f3f0ea;font:14px/1.6 system-ui,sans-serif}'
            'main{max-width:840px;margin:auto}p{color:#aaa9a3}'
            'div{display:flex;flex-wrap:wrap;gap:24px;margin-bottom:24px;align-items:center}</style>'
            '<main><h1>Roamtty Logo</h1><p>终端里的小精灵站在终端提示符上。每行依次为方形、圆形和系统主题色图标，'
            '由 scripts/render-launcher-icon.py 生成。</p>'
            f'{rows}</main></html>')

main()
