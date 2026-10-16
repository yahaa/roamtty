#!/usr/bin/env python3
"""Builds the roamtty.com website from site/ and docs/PRIVACY.md, and publishes it to GitHub Pages.

The app repository stays private; the generated site is pushed to the public yahaa/roamtty-site repository,
which GitHub Pages serves at the custom domain roamtty.com (DNS and TLS through Cloudflare, see docs/WEBSITE.md).

  scripts/site.py --out DIR    build into DIR only (preview with `python3 -m http.server -d DIR`)
  scripts/site.py              build and publish

Chinese pages live at the site root and English ones under /en/. /privacy/ carries both languages so a single URL
can be given to Google Play and the App Store. Markdown is rendered through GitHub's API, so `gh` must be signed in.
"""
from __future__ import annotations

import argparse
import datetime
import html
import pathlib
import re
import shutil
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
SITE = ROOT / "site"
SITE_REPO = "yahaa/roamtty-site"
DOMAIN = "roamtty.com"
EMAIL = "support@roamtty.com"
# Store listings; leave empty until the app is live there and the button reads "coming soon".
PLAY_URL = ""
APP_STORE_URL = ""

LANGS = {
    "zh": {"prefix": "/", "html": "zh-CN", "other": "en", "switch": "English", "soon": "即将上架",
           "get": {"play": "立即下载", "ios": "立即下载"},
           "nav": [("/#features", "功能", "features"), ("/support/", "支持", "support"), ("/privacy/", "隐私", "privacy")],
           "footer": [("/support/", "支持与联系"), ("/privacy/", "隐私政策"), ("/terms/", "使用条款")],
           "tm": "Claude Code、Codex、Grok 是其各自所有者的商标，Roamtty 与这些公司没有关联。Google Play 是 Google LLC 的商标；"
                 "App Store、iPhone 是 Apple Inc. 的商标。"},
    "en": {"prefix": "/en/", "html": "en", "other": "zh", "switch": "中文", "soon": "Coming soon",
           "get": {"play": "Get it on", "ios": "Download on the"},
           "nav": [("/en/#features", "Features", "features"), ("/en/support/", "Support", "support"),
                   ("/en/privacy/", "Privacy", "privacy")],
           "footer": [("/en/support/", "Support & contact"), ("/en/privacy/", "Privacy policy"), ("/en/terms/", "Terms of use")],
           "tm": "Claude Code, Codex and Grok are trademarks of their respective owners; Roamtty is not affiliated with them. "
                 "Google Play is a trademark of Google LLC. App Store and iPhone are trademarks of Apple Inc."},
}
PLAY_ICON = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 2.6v18.8c0 .5.5.8 1 .6l15.6-8.9c.5-.3.5-1 0-1.3L5 2c-.5-.2-1 .1-1 .6z"/></svg>'
APPLE_ICON = ('<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M16.4 12.6c0-2.6 2.1-3.8 2.2-3.9-1.2-1.8-3.1-2-3.7-2-1.6-.2'
              '-3.1.9-3.9.9-.8 0-2-.9-3.4-.9-1.7 0-3.3 1-4.2 2.6-1.8 3.1-.5 7.7 1.3 10.2.9 1.2 1.9 2.6 3.2 2.6 1.3-.1 1.8-.8 3.3-.8'
              ' 1.6 0 2 .8 3.4.8 1.4 0 2.3-1.2 3.1-2.5 1-1.4 1.4-2.8 1.4-2.9 0 0-2.7-1-2.7-4.1zM13.9 4.9c.7-.9 1.2-2 1-3.2-1 .1-2.2.7'
              '-3 1.6-.6.7-1.2 1.9-1 3.1 1.1.1 2.3-.6 3-1.5z"/></svg>')


def render_markdown(markdown: str) -> str:
    return subprocess.run(["gh", "api", "markdown", "-f", "mode=markdown", "-F", "text=@-"],
                          input=markdown, capture_output=True, text=True, check=True).stdout


def store_buttons(lang: str) -> str:
    strings = LANGS[lang]
    buttons = []
    for url, icon, kind, name in ((PLAY_URL, PLAY_ICON, "play", "Google Play"), (APP_STORE_URL, APPLE_ICON, "ios", "App Store")):
        if url:
            buttons.append(f'<a class="store" href="{html.escape(url)}">{icon}<span><small>{strings["get"][kind]}</small>'
                           f'<strong>{name}</strong></span></a>')
        else:
            buttons.append(f'<span class="store soon" aria-disabled="true">{icon}<span><small>{strings["soon"]}</small>'
                           f'<strong>{name}</strong></span></span>')
    return '<div class="stores">' + "".join(buttons) + "</div>"


def page(lang: str, path: str, title: str, description: str, body: str, current: str = "", alternate: str | None = None) -> str:
    """`path` is this page's URL path, `alternate` the same page in the other language (None when there is none)."""
    strings = LANGS[lang]
    other = LANGS[strings["other"]]
    nav = "".join(f'<a class="opt" href="{href}"' + (' aria-current="page"' if key == current else "") + f'>{label}</a>'
                  for href, label, key in strings["nav"])
    switch = f'<a class="lang" href="{alternate}" hreflang="{other["html"]}" lang="{other["html"]}">{strings["switch"]}</a>' \
        if alternate else ""
    hreflang = "" if not alternate else (
        f'<link rel="alternate" hreflang="{strings["html"]}" href="https://{DOMAIN}{path}">\n'
        f'<link rel="alternate" hreflang="{other["html"]}" href="https://{DOMAIN}{alternate}">\n')
    footer = "".join(f'<a href="{href}">{label}</a>' for href, label in strings["footer"])
    return f"""<!doctype html>
<html lang="{strings["html"]}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{html.escape(title)}</title>
<meta name="description" content="{html.escape(description)}">
<meta name="theme-color" content="#262728">
<link rel="canonical" href="https://{DOMAIN}{path}">
{hreflang}<link rel="icon" type="image/png" sizes="32x32" href="/assets/favicon-32.png">
<link rel="apple-touch-icon" href="/assets/apple-touch-icon.png">
<link rel="stylesheet" href="/assets/style.css">
<meta property="og:type" content="website">
<meta property="og:site_name" content="Roamtty">
<meta property="og:title" content="{html.escape(title)}">
<meta property="og:description" content="{html.escape(description)}">
<meta property="og:url" content="https://{DOMAIN}{path}">
<meta property="og:image" content="https://{DOMAIN}/assets/og.png">
<meta name="twitter:card" content="summary_large_image">
</head>
<body>
<a class="skip" href="#main">{"跳到正文" if lang == "zh" else "Skip to content"}</a>
<header class="site-header"><div class="wrap">
<a class="brand" href="{strings["prefix"]}"><img src="/assets/icon-512.png" alt="" width="30" height="30">Roamtty</a>
<nav class="site-nav" aria-label="{"主导航" if lang == "zh" else "Main"}">{nav}{switch}</nav>
</div></header>
<main id="main">
{body}
</main>
<footer class="site-footer"><div class="wrap">
<nav aria-label="{"页脚" if lang == "zh" else "Footer"}">{footer}<a href="mailto:{EMAIL}">{EMAIL}</a></nav>
<span>© {datetime.date.today().year} Roamtty contributors · Apache-2.0</span>
<p class="tm">{strings["tm"]}</p>
</div></footer>
</body>
</html>
"""


def fragment(name: str, lang: str) -> tuple[dict, str]:
    """site/pages/<name>.<lang>.html: `<!-- key: value -->` header lines (title, description, nav), then the body."""
    text = (SITE / "pages" / f"{name}.{lang}.html").read_text(encoding="utf-8")
    meta = {}
    while match := re.match(r"\s*<!--\s*(\w+):\s*(.*?)\s*-->\n", text):
        meta[match.group(1)] = match.group(2)
        text = text[match.end():]
    for key, value in {"{{stores}}": store_buttons(lang), "{{email}}": EMAIL}.items():
        text = text.replace(key, value)
    return meta, text


def privacy_sections() -> tuple[str, str]:
    policy = (ROOT / "docs/PRIVACY.md").read_text(encoding="utf-8")
    # Same layout the Android app relies on: the Chinese policy, then the English one, each under its own `# ` heading.
    starts = [m.start() for m in re.finditer(r"^# ", policy, re.MULTILINE)]
    if len(starts) != 2:
        raise SystemExit("docs/PRIVACY.md must contain exactly two top-level sections (Chinese, then English)")
    return render_markdown(policy[starts[0]:starts[1]]), render_markdown(policy[starts[1]:])


def write(out: pathlib.Path, path: str, content: str) -> None:
    target = out / path.lstrip("/")
    if path.endswith("/"):
        target = target / "index.html"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")


def build(out: pathlib.Path) -> None:
    """Fills `out`, which must not exist yet, be empty, or be a checkout of the site repository (replaced wholesale)."""
    children = [child for child in out.iterdir() if child.name != ".git"] if out.exists() else []
    if children and not (out / ".git").exists() and not (out / "CNAME").exists():
        raise SystemExit(f"{out} is not empty and not a site checkout; refusing to overwrite it")
    for child in children:
        shutil.rmtree(child) if child.is_dir() else child.unlink()
    out.mkdir(parents=True, exist_ok=True)
    shutil.copytree(SITE / "assets", out / "assets")
    urls = []
    for name in ("index", "support", "terms"):
        slug = "" if name == "index" else f"{name}/"
        for lang in LANGS:
            meta, body = fragment(name, lang)
            path, alternate = LANGS[lang]["prefix"] + slug, LANGS[LANGS[lang]["other"]]["prefix"] + slug
            write(out, path, page(lang, path, meta["title"], meta["description"], body, meta.get("nav", ""), alternate))
            urls.append(path)

    chinese, english = privacy_sections()
    toc = '<p class="toc"><a href="#zh" lang="zh-CN">中文</a><a href="#en" lang="en">English</a></p>'
    write(out, "/privacy/", page("zh", "/privacy/", "隐私政策 Privacy Policy · Roamtty",
                                 "Roamtty 隐私政策：不收集任何个人数据。Roamtty Privacy Policy: no personal data is collected.",
                                 f'<article class="doc"><div class="wrap narrow">{toc}<div class="policy" id="zh" lang="zh-CN">'
                                 f'{chinese}</div><div class="policy" id="en" lang="en">{english}</div></div></article>',
                                 "privacy", "/en/privacy/"))
    write(out, "/en/privacy/", page("en", "/en/privacy/", "Privacy Policy · Roamtty",
                                    "Roamtty Privacy Policy: the app collects no personal data.",
                                    f'<article class="doc"><div class="wrap narrow"><div class="policy">{english}</div></div></article>',
                                    "privacy", "/privacy/"))
    urls += ["/privacy/", "/en/privacy/"]

    meta, body = fragment("404", "zh")
    write(out, "/404.html", page("zh", "/404.html", meta["title"], meta["description"], body))

    today = datetime.date.today().isoformat()
    write(out, "/sitemap.xml", '<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n'
          + "".join(f"<url><loc>https://{DOMAIN}{u}</loc><lastmod>{today}</lastmod></url>\n" for u in urls) + "</urlset>\n")
    write(out, "/robots.txt", f"User-agent: *\nAllow: /\nSitemap: https://{DOMAIN}/sitemap.xml\n")
    expires = (datetime.date.today() + datetime.timedelta(days=365)).isoformat()
    write(out, "/.well-known/security.txt", f"Contact: mailto:{EMAIL}\nExpires: {expires}T00:00:00Z\n"
          f"Preferred-Languages: zh, en\nCanonical: https://{DOMAIN}/.well-known/security.txt\nPolicy: https://{DOMAIN}/en/support/#security\n")
    write(out, "/CNAME", DOMAIN + "\n")
    write(out, "/.nojekyll", "")
    write(out, "/README.md", f"# roamtty-site\n\nRoamtty website: https://{DOMAIN} (generated by `scripts/site.py` in the app "
          "repository; do not edit by hand)\n")


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
        subprocess.run(git + ["commit", "-q", "-m", f"Publish site from app repository {source}"], check=True)
        subprocess.run(git + ["push", "-q", "origin", "HEAD"], check=True)
        print(f"Published https://{DOMAIN}/")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", type=pathlib.Path, help="build into this directory without publishing")
    args = parser.parse_args()
    if args.out:
        build(args.out)
        print(f"Built site into {args.out}")
    else:
        publish()
