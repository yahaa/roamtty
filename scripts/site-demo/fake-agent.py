#!/usr/bin/env python3
"""Stand-in for claude / codex for website screenshots: draws a plausible agent screen for the folder it starts in
and reports its state through the tmux options Roamtty reads (see agent-hook.sh). Language from ~/.demo-lang."""
import os, pathlib, signal, subprocess, sys, time

agent = os.path.basename(sys.argv[0])
folder = os.path.basename(os.getcwd())
lang = (pathlib.Path.home() / ".demo-lang").read_text().strip() if (pathlib.Path.home() / ".demo-lang").exists() else "zh"
zh = lang == "zh"
pane = os.environ.get("TMUX_PANE")

R = "\x1b[0m"; B = "\x1b[1m"; D = "\x1b[2m"; G = "\x1b[32m"; RD = "\x1b[31m"; Y = "\x1b[33m"
O = "\x1b[38;5;209m"; C = "\x1b[36m"; GRY = "\x1b[38;5;245m"; BG_ADD = "\x1b[48;5;22m"; BG_DEL = "\x1b[48;5;52m"


def tmux_set(option, value):
    if pane:
        subprocess.run(["tmux", "set-option", "-q", "-t", pane, option, value], stderr=subprocess.DEVNULL)


def report(state, tool="", detail="", task=None, worked=None, reply=None, ago=0):
    if task is not None:
        tmux_set("@roamtty_task", task)
    if worked is not None:
        tmux_set("@roamtty_worked", str(worked))
    if reply is not None:
        tmux_set("@roamtty_reply", reply)
    tmux_set("@roamtty_agent", f"{state}|{int(time.time()) - ago}|{agent}|{tool}|{detail}")


OUT = []


def emit(*parts, end="\n"):
    OUT.append("".join(parts) + end)


def width():
    try:
        return os.get_terminal_size().columns
    except OSError:
        return 44


def box(lines):
    w = min(width(), 60) - 2
    out = [GRY + "╭" + "─" * w + "╮" + R]
    for text, visible in lines:
        out.append(GRY + "│" + R + text + " " * max(0, w - visible) + GRY + "│" + R)
    out.append(GRY + "╰" + "─" * w + "╯" + R)
    return "\n".join(out)


def claude_app():
    task = "重构登录模块并补充测试" if zh else "Refactor login and add tests"
    say = "先读一下现有的会话逻辑。" if zh else "Reading the session logic first."
    report("working", "Bash", "npm test", task=task, ago=38)
    emit(f"{O}✻{R} {B}Claude Code{R}  {D}~/acme-app{R}\n")
    if zh:
        emit(f"{GRY}>{R} 先跑一下测试，看看哪里不稳定\n")
        emit(f"{B}●{R} 40 个测试通过，2 个在令牌过期时偶发失败：\n  session.ts 直接比较 exp，过期后没有刷新。\n")
    else:
        emit(f"{GRY}>{R} Run the tests and see what is flaky\n")
        emit(f"{B}●{R} 40 tests pass; 2 fail now and then when a\n  token expires: session.ts compares exp\n  directly and never refreshes.\n")
    emit(f"{GRY}>{R} {task}\n")
    emit(f"{B}●{R} {say}\n")
    emit(f"{G}●{R} {B}Search{R}(pattern: \"token.exp\")\n  {D}⎿  Found 3 files{R}\n")
    todo = (["读懂现有的会话逻辑", "抽出 isExpired 并在过期时刷新", "补充单元测试", "运行完整测试"] if zh else
            ["Read the session logic", "Extract isExpired", "Add unit tests", "Run the full suite"])
    emit(f"{G}●{R} {B}Update Todos{R}")
    for i, item in enumerate(todo):
        mark = f"{G}☒{R} {D}{item}{R}" if i < 3 else f"☐ {B}{item}{R}"
        emit(f"  {D}⎿{R}  {mark}" if i == 0 else f"     {mark}")
    emit("")
    emit(f"{G}●{R} {B}Read{R}(src/auth/session.ts)\n  {D}⎿  Read 84 lines{R}\n")
    emit(f"{G}●{R} {B}Update{R}(src/auth/session.ts)\n  {D}⎿  Updated with 9 additions, 2 removals{R}")
    emit(f"     {D}41{R} {BG_DEL}-  if (token.exp < now) return null   {R}")
    emit(f"     {D}41{R} {BG_ADD}+  if (isExpired(token, now)) {{       {R}")
    emit(f"     {D}42{R} {BG_ADD}+    return refresh(token)            {R}")
    emit(f"     {D}43{R} {BG_ADD}+  }}                                  {R}\n")
    emit(f"{G}●{R} {B}Write{R}(src/auth/session.test.ts)\n  {D}⎿  Wrote 58 lines{R}\n")
    emit(f"{G}●{R} {B}Bash{R}(npm test)")
    emit(f"  {D}⎿{R}  {G}✓{R} refreshes an expired token {D}(12 ms){R}")
    emit(f"     {G}✓{R} rejects a revoked token {D}(4 ms){R}")
    emit(f"     {D}… 40 more{R}\n")
    emit(f"{O}✻ {'正在运行测试…' if zh else 'Running tests…'}{R} {D}(38s · esc to interrupt){R}\n")
    emit(box([(f" {GRY}>{R} ", 3)]))


def codex_infra():
    task = "修复 CI 中的构建缓存" if zh else "Fix the build cache in CI"
    report("waiting", "exec_command", "git push origin fix/ci-cache", task=task, ago=95)
    emit(f"{B}>_ Codex{R}  {D}~/infra{R}\n")
    emit(f"{C}›{R} {task}\n")
    if zh:
        emit(f"{B}•{R} 缓存键里带了每次运行都会变的 run_id，\n  所以构建从来没有命中缓存。改成按\n  lockfile 的哈希生成。\n")
    else:
        emit(f"{B}•{R} The cache key included run_id, which\n  changes every run, so builds never hit\n  the cache. It now uses the lockfile hash.\n")
    emit(f"{B}•{R} Edited {C}.github/workflows/ci.yml{R} {G}+1{R} {RD}-1{R}")
    emit(f"    {D}18{R} {RD}-  deps-${{{{ github.run_id }}}}{R}")
    emit(f"    {D}18{R} {G}+  deps-${{{{ hashFiles('package-lock.json') }}}}{R}\n")
    emit(f"{B}•{R} Ran {C}npm ci && npm run build{R}\n  {D}└ built in 41s · cache hit{R}\n")
    emit(f"{Y}{'需要你的确认' if zh else 'Approval needed'}{R}")
    emit(f"  {D}${R} git push origin fix/ci-cache\n")
    emit(f"  {C}› 1. Yes, proceed{R}")
    emit(f"    2. Yes, and don't ask again")
    emit(f"    3. No, tell Codex what to do\n")


def claude_blog():
    task = "首页图片改为懒加载" if zh else "Lazy-load the images on the home page"
    reply = ("已把首页 14 张图片改为懒加载，Lighthouse 性能分从 71 提升到 94。" if zh else
             "Lazy-loaded the 14 images on the home page; Lighthouse performance went from 71 to 94.")
    report("idle", task=task, worked=252, reply=reply, ago=240)
    emit(f"{O}✻{R} {B}Claude Code{R}  {D}~/blog{R}\n")
    emit(f"{GRY}>{R} {task}\n")
    emit(f"{G}●{R} {B}Update{R}(src/components/Hero.astro)\n  {D}⎿  Updated with 3 additions{R}\n")
    emit(f"{G}●{R} {B}Bash{R}(npx lighthouse http://localhost:4321)\n  {D}⎿  Performance 94{R}\n")
    emit(f"{B}●{R} {reply}\n")
    emit(f"{D}✻ Worked for 4m 12s{R}\n")
    emit(box([(f" {GRY}>{R} ", 3)]))


def main():
    signal.signal(signal.SIGINT, lambda *_: (tmux_set("@roamtty_agent", ""), sys.exit(0)))
    {"acme-app": claude_app, "infra": codex_infra, "blog": claude_blog}.get(folder, claude_blog)()
    text = "".join(OUT).rstrip("\n")

    def draw(*_):
        # Drop the launch command from tmux's history too, or a taller window pulls it back into view.
        if pane:
            subprocess.run(["tmux", "clear-history", "-t", pane], stderr=subprocess.DEVNULL)
        rows = os.get_terminal_size().lines if sys.stdout.isatty() else 40
        pad = max(0, rows - text.count("\n") - 1)
        sys.stdout.write("\x1b[2J\x1b[3J\x1b[H" + "\n" * pad + text)
        if folder == "infra":
            sys.stdout.write("\x1b[?25l")
        else:
            sys.stdout.write("\x1b[1A\x1b[2G\x1b[C\x1b[C\x1b[C")
        sys.stdout.flush()

    draw()
    signal.signal(signal.SIGWINCH, draw)
    for line in sys.stdin:
        pass
    while True:
        time.sleep(3600)


main()
