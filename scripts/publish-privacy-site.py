#!/usr/bin/env python3
"""Publishes docs/PRIVACY.md to https://roamtty.com/privacy/ through the public GitHub Pages repository.

The app repository stays private; only the rendered policy and a minimal home page are published.
Requires an authenticated `gh` CLI. `--out DIR` only builds the site into DIR without publishing.
"""
import argparse
import html
import pathlib
import re
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
SITE_REPO = "yahaa/roamtty-site"
DOMAIN = "roamtty.com"

STYLE = """
:root { color-scheme: light dark; --bg: #faf9f7; --fg: #24221f; --muted: #6b665f; --rule: #e4e0da;
  --accent: #b5562f; --code: #f0ece6; }
@media (prefers-color-scheme: dark) { :root { --bg: #1f1e1c; --fg: #ece8e2; --muted: #a39d94; --rule: #3a3733;
  --accent: #f0a27e; --code: #2c2a27; } }
* { box-sizing: border-box; }
body { margin: 0; background: var(--bg); color: var(--fg); line-height: 1.7;
  font: 16px/1.7 system-ui, -apple-system, "Segoe UI", "PingFang SC", "Noto Sans CJK SC", sans-serif; }
main { max-width: 760px; margin: 0 auto; padding: 32px 16px 64px; }
nav { display: flex; gap: 16px; font-size: 14px; margin-bottom: 24px; }
a { color: var(--accent); }
h1 { font-size: 28px; line-height: 1.3; margin: 8px 0 4px; }
h2 { font-size: 20px; margin-top: 32px; padding-bottom: 4px; border-bottom: 1px solid var(--rule); }
section + section { margin-top: 64px; padding-top: 32px; border-top: 1px solid var(--rule); }
table { width: 100%; border-collapse: collapse; font-size: 15px; display: block; overflow-x: auto; }
th, td { text-align: left; padding: 8px 10px; border: 1px solid var(--rule); vertical-align: top; }
code { background: var(--code); padding: 1px 5px; border-radius: 4px; font-size: 0.9em; }
.anchor { display: none; }
footer { margin-top: 48px; color: var(--muted); font-size: 14px; }
"""


def render(markdown: str) -> str:
    return subprocess.run(["gh", "api", "markdown", "-f", "mode=markdown", "-F", "text=@-"],
                          input=markdown, capture_output=True, text=True, check=True).stdout


def page(title: str, lang: str, description: str, body: str) -> str:
    return f"""<!doctype html>
<html lang="{lang}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{html.escape(title)}</title>
<meta name="description" content="{html.escape(description)}">
<style>{STYLE}</style>
</head>
<body>
<main>
{body}
<footer>© 2026 Roamtty contributors</footer>
</main>
</body>
</html>
"""


def build(out: pathlib.Path) -> None:
    policy = (ROOT / "docs/PRIVACY.md").read_text(encoding="utf-8")
    # Same layout the app relies on: the Chinese policy, then the English one, each under its own `# ` heading.
    starts = [m.start() for m in re.finditer(r"^# ", policy, re.MULTILINE)]
    if len(starts) != 2:
        raise SystemExit("docs/PRIVACY.md must contain exactly two top-level sections (Chinese, then English)")
    chinese, english = policy[starts[0]:starts[1]], policy[starts[1]:]
    privacy = page("Roamtty 隐私政策 · Privacy Policy", "zh-CN", "Roamtty 隐私政策 / Roamtty Privacy Policy", f"""
<nav><a href="/">Roamtty</a><a href="#zh">中文</a><a href="#en">English</a></nav>
<section id="zh" lang="zh-CN">
{render(chinese)}</section>
<section id="en" lang="en">
{render(english)}</section>""")
    home = page("Roamtty", "zh-CN", "Roamtty: Android SSH client for remote AI coding", """
<h1>Roamtty</h1>
<p>面向远程 AI 编程的免费开源 Android SSH 客户端：tmux 会话保持、mosh 漫游、SFTP 文件管理。</p>
<p lang="en">A free, open-source Android SSH client for remote AI coding, with tmux session persistence, mosh roaming and SFTP.</p>
<p><a href="/privacy/">隐私政策 · Privacy Policy</a></p>""")
    (out / "privacy").mkdir(parents=True, exist_ok=True)
    (out / "privacy/index.html").write_text(privacy, encoding="utf-8")
    (out / "index.html").write_text(home, encoding="utf-8")
    (out / "CNAME").write_text(DOMAIN + "\n", encoding="utf-8")
    (out / ".nojekyll").write_text("", encoding="utf-8")


def publish() -> None:
    with tempfile.TemporaryDirectory() as temp:
        site = pathlib.Path(temp) / "site"
        subprocess.run(["gh", "repo", "clone", SITE_REPO, str(site), "--", "--quiet"], check=True)
        build(site)
        git = ["git", "-C", str(site)]
        subprocess.run(git + ["add", "-A"], check=True)
        if subprocess.run(git + ["diff", "--cached", "--quiet"]).returncode == 0:
            print("Site already up to date.")
            return
        source = subprocess.run(["git", "-C", str(ROOT), "rev-parse", "--short", "HEAD"],
                                capture_output=True, text=True, check=True).stdout.strip()
        subprocess.run(git + ["commit", "-q", "-m", f"Publish privacy policy from app repository {source}"], check=True)
        subprocess.run(git + ["push", "-q", "origin", "HEAD"], check=True)
        print(f"Published https://{DOMAIN}/privacy/")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", type=pathlib.Path, help="build into this directory without publishing")
    args = parser.parse_args()
    if args.out:
        build(args.out)
        print(f"Built site into {args.out}")
    else:
        publish()
