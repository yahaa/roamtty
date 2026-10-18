#!/usr/bin/env python3
"""Builds the roamtty.com website from site/ and docs/PRIVACY.md, and publishes it to GitHub Pages.

The app repository stays private; the generated site is pushed to the public yahaa/roamtty-site repository,
which GitHub Pages serves at the custom domain roamtty.com (DNS and TLS through Cloudflare, see docs/WEBSITE.md).

  scripts/site.py --out DIR    build into DIR only (preview with `python3 -m http.server -d DIR`)
  scripts/site.py              build and publish

Chinese pages live at the site root and English ones under /en/. /privacy/ carries both languages so a single URL
can be given to Google Play and the App Store. Blog posts are site/blog/<slug>.<lang>.html, served at /blog/<slug>/.
Markdown is rendered through GitHub's API, so `gh` must be signed in.
"""
from __future__ import annotations

import argparse
import datetime
import hashlib
import html
import json
import pathlib
import re
import shutil
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
SITE = ROOT / "site"
SITE_REPO = "yahaa/roamtty-site"
# Browsers keep assets for hours (max-age=14400), so the stylesheet URL changes whenever its content does.
CSS_VERSION = hashlib.sha256((SITE / "assets/style.css").read_bytes()).hexdigest()[:10]
DOMAIN = "roamtty.com"
EMAIL = "support@roamtty.com"
# Store listings; leave empty until the app is live there and the button reads "coming soon".
PLAY_URL = ""
APP_STORE_URL = ""

LANGS = {
    "zh": {"prefix": "/", "html": "zh-CN", "locale": "zh_CN", "other": "en", "switch": "English", "soon": "即将上架",
           "get": {"play": "立即下载", "ios": "立即下载"},
           "nav": [("/#features", "功能", "features"), ("/docs/", "文档", "docs"), ("/blog/", "博客", "blog"), ("/support/", "支持", "support"),
                   ("/privacy/", "隐私", "privacy")],
           "footer": [("/docs/", "使用文档"), ("/blog/", "博客"), ("/support/", "支持与联系"), ("/privacy/", "隐私政策"), ("/terms/", "使用条款")],
           "blog": {"title": "博客", "lede": "远程开发、组网与 Roamtty 使用技巧。", "back": "← 全部文章", "toc": "目录",
                    "description": "Roamtty 博客：远程开发、Tailscale / Headscale 组网与 Roamtty 使用技巧。"},
           "tm": "Claude Code、Codex、Grok 是其各自所有者的商标，Roamtty 与这些公司没有关联。Google Play 是 Google LLC 的商标；"
                 "App Store、iPhone 是 Apple Inc. 的商标。"},
    "en": {"prefix": "/en/", "html": "en", "locale": "en_US", "other": "zh", "switch": "中文", "soon": "Coming soon",
           "get": {"play": "Get it on", "ios": "Download on the"},
           "nav": [("/en/#features", "Features", "features"), ("/en/docs/", "Docs", "docs"), ("/en/blog/", "Blog", "blog"),
                   ("/en/support/", "Support", "support"),
                   ("/en/privacy/", "Privacy", "privacy")],
           "footer": [("/en/docs/", "Documentation"), ("/en/blog/", "Blog"), ("/en/support/", "Support & contact"),
                      ("/en/privacy/", "Privacy policy"), ("/en/terms/", "Terms of use")],
           "blog": {"title": "Blog", "lede": "Notes on remote development, networking and getting more out of Roamtty.", "back": "← All posts",
                    "toc": "Contents", "description": "The Roamtty blog: remote development, Tailscale and Headscale networking, and Roamtty tips."},
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


def updated(*sources: pathlib.Path) -> str:
    """The date of the latest commit touching any of `sources`, or today for files not committed yet."""
    dates = [subprocess.run(["git", "-C", str(ROOT), "log", "-1", "--format=%cs", "--", str(source)],
                            capture_output=True, text=True, check=True).stdout.strip() for source in sources]
    return max(dates) if all(dates) else datetime.date.today().isoformat()


def plain(fragment_html: str) -> str:
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", "", fragment_html))).strip()


def json_ld(data: dict) -> str:
    # `<\/` keeps a "</script>" inside a string from closing the element early.
    return ('<script type="application/ld+json">' + json.dumps(data, ensure_ascii=False).replace("</", "<\\/") + "</script>\n")


def organization() -> dict:
    return {"@type": "Organization", "@id": f"https://{DOMAIN}/#organization", "name": "Roamtty", "url": f"https://{DOMAIN}/",
            "logo": f"https://{DOMAIN}/assets/icon-512.png", "email": EMAIL}


def app_schema(lang: str, description: str) -> dict:
    """The home page describes the app itself; Organization rides along so other pages can point at it by @id."""
    prefix = LANGS[lang]["prefix"]
    return {"@context": "https://schema.org", "@graph": [
        organization(),
        {"@type": "WebSite", "@id": f"https://{DOMAIN}/#website", "name": "Roamtty", "url": f"https://{DOMAIN}/",
         "inLanguage": ["zh-CN", "en"], "publisher": {"@id": f"https://{DOMAIN}/#organization"}},
        {"@type": "MobileApplication", "name": "Roamtty", "url": f"https://{DOMAIN}{prefix}", "description": description,
         "applicationCategory": "DeveloperApplication", "operatingSystem": "Android 8.0+, iOS 16+",
         "inLanguage": ["zh-CN", "en"], "image": f"https://{DOMAIN}/assets/icon-512.png",
         "screenshot": f"https://{DOMAIN}/assets/screens/sessions-{lang}.webp",
         "publisher": {"@id": f"https://{DOMAIN}/#organization"},
         **({"sameAs": [url for url in (PLAY_URL, APP_STORE_URL) if url]} if PLAY_URL or APP_STORE_URL else {})}]}


def faq_schema(body: str) -> dict:
    """FAQPage from the `<details><summary>question</summary>answer</details>` blocks of the support page."""
    return {"@context": "https://schema.org", "@type": "FAQPage", "mainEntity": [
        {"@type": "Question", "name": plain(question), "acceptedAnswer": {"@type": "Answer", "text": plain(answer)}}
        for question, answer in re.findall(r"<details><summary>(.*?)</summary>(.*?)</details>", body, re.DOTALL)]}


def post_schema(lang: str, path: str, meta: dict, modified: str) -> dict:
    return {"@context": "https://schema.org", "@type": "BlogPosting", "headline": meta["title"], "description": meta["description"],
            "datePublished": meta["date"], "dateModified": max(meta["date"], modified), "inLanguage": LANGS[lang]["html"],
            "mainEntityOfPage": f"https://{DOMAIN}{path}", "image": f"https://{DOMAIN}/assets/og.png",
            "author": organization(), "publisher": {"@id": f"https://{DOMAIN}/#organization"}}


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


def page(lang: str, path: str, title: str, description: str, body: str, current: str = "", alternate: str | None = None,
         og_type: str = "website", schema: dict | None = None) -> str:
    """`path` is this page's URL path, `alternate` the same page in the other language (None when there is none).
    `schema` is emitted as JSON-LD."""
    strings = LANGS[lang]
    other = LANGS[strings["other"]]
    # The docs and blog links stay visible on narrow screens, where the other options fold away.
    nav = "".join(f'<a class="opt{" keep" if key in ("docs", "blog") else ""}" href="{href}"' + (' aria-current="page"' if key == current else "") + f'>{label}</a>'
                  for href, label, key in strings["nav"])
    switch = f'<a class="lang" href="{alternate}" hreflang="{other["html"]}" lang="{other["html"]}">{strings["switch"]}</a>' \
        if alternate else ""
    hreflang = "" if not alternate else (
        f'<link rel="alternate" hreflang="{strings["html"]}" href="https://{DOMAIN}{path}">\n'
        f'<link rel="alternate" hreflang="{other["html"]}" href="https://{DOMAIN}{alternate}">\n'
        # Visitors in neither language get the English page.
        f'<link rel="alternate" hreflang="x-default" href="https://{DOMAIN}{path if lang == "en" else alternate}">\n')
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
<link rel="stylesheet" href="/assets/style.css?v={CSS_VERSION}">
<meta property="og:type" content="{og_type}">
<meta property="og:site_name" content="Roamtty">
<meta property="og:locale" content="{strings["locale"]}">
{f'<meta property="og:locale:alternate" content="{other["locale"]}">{chr(10)}' if alternate else ""}<meta property="og:title" content="{html.escape(title)}">
<meta property="og:description" content="{html.escape(description)}">
<meta property="og:url" content="https://{DOMAIN}{path}">
<meta property="og:image" content="https://{DOMAIN}/assets/og.png">
<meta name="twitter:card" content="summary_large_image">
{json_ld(schema) if schema else ""}</head>
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
<span>© {datetime.date.today().year} Roamtty</span>
<p class="tm">{strings["tm"]}</p>
</div></footer>
</body>
</html>
"""


def fragment(name: str, lang: str, folder: str = "pages") -> tuple[dict, str]:
    """site/<folder>/<name>.<lang>.html: `<!-- key: value -->` header lines (title, description, nav), then the body."""
    text = (SITE / folder / f"{name}.{lang}.html").read_text(encoding="utf-8")
    meta = {}
    while match := re.match(r"\s*<!--\s*(\w+):\s*(.*?)\s*-->\n", text):
        meta[match.group(1)] = match.group(2)
        text = text[match.end():]
    for key, value in {"{{stores}}": store_buttons(lang), "{{email}}": EMAIL}.items():
        text = text.replace(key, value)
    return meta, text


def posts(lang: str) -> list[tuple[str, dict, str]]:
    """(slug, meta, body) for each site/blog/<slug>.<lang>.html, newest first. Header lines: title, description, date."""
    found = []
    for file in (SITE / "blog").glob(f"*.{lang}.html"):
        slug = file.name.removesuffix(f".{lang}.html")
        meta, body = fragment(slug, lang, "blog")
        found.append((slug, meta, body))
    return sorted(found, key=lambda post: (post[1]["date"], post[0]), reverse=True)


def long_date(iso: str, lang: str) -> str:
    day = datetime.date.fromisoformat(iso)
    return f"{day.year} 年 {day.month} 月 {day.day} 日" if lang == "zh" else f"{day:%B} {day.day}, {day.year}"


def blog_index(lang: str, entries: list[tuple[str, dict, str]]) -> str:
    strings = LANGS[lang]["blog"]
    cards = "".join(f'<li><a class="post-card" href="{LANGS[lang]["prefix"]}blog/{slug}/"><time datetime="{meta["date"]}">'
                    f'{long_date(meta["date"], lang)}</time><h2>{html.escape(meta["title"])}</h2>'
                    f'<p>{html.escape(meta["description"])}</p></a></li>' for slug, meta, _ in entries)
    return (f'<article class="doc"><div class="wrap narrow"><h1>{strings["title"]}</h1><p class="meta">{strings["lede"]}</p>'
            f'<ul class="posts">{cards}</ul></div></article>')


def blog_post(lang: str, meta: dict, body: str) -> str:
    """A post is laid out like the docs: its `<h2 id>` headings become the contents column."""
    strings = LANGS[lang]["blog"]
    toc = "".join(f'<li><a href="#{anchor}">{re.sub(r"<[^>]+>", "", heading)}</a></li>'
                  for anchor, heading in re.findall(r'<h2 id="([^"]+)">(.*?)</h2>', body))
    return (f'<article class="doc docs post"><div class="wrap docs-layout">\n'
            f'<nav class="docs-toc" aria-label="{strings["toc"]}"><p class="toc-title">{strings["toc"]}</p><ol>{toc}</ol></nav>\n'
            f'<div class="docs-body">\n<p class="crumb"><a href="{LANGS[lang]["prefix"]}blog/">{strings["back"]}</a></p>\n'
            f'<h1>{html.escape(meta["title"])}</h1>\n<p class="meta"><time datetime="{meta["date"]}">{long_date(meta["date"], lang)}</time></p>\n'
            f'{body}</div>\n</div></article>')


def privacy_sections() -> tuple[str, str]:
    policy = (ROOT / "docs/PRIVACY.md").read_text(encoding="utf-8")
    # Same layout the Android app relies on: the Chinese policy, then the English one, each under its own `# ` heading.
    starts = [m.start() for m in re.finditer(r"^# ", policy, re.MULTILINE)]
    if len(starts) != 2:
        raise SystemExit("docs/PRIVACY.md must contain exactly two top-level sections (Chinese, then English)")
    return render_markdown(policy[starts[0]:starts[1]]), render_markdown(policy[starts[1]:])


def llms_txt(listing: dict[str, list[tuple[str, str, str]]]) -> str:
    """https://llmstxt.org: a Markdown map of the site for language models, English first."""
    _, _, summary = listing["en"][0]
    sections = {"en": "## English", "zh": "## 中文 (Simplified Chinese)"}
    return (f"# Roamtty\n\n> {summary}\n\n"
            "Roamtty is an SSH client, not an AI model: Claude Code, Codex, Grok or Gemini CLI run on the user's own server, and "
            "Roamtty starts them over SSH and shows their status on the phone. The app has no accounts or backend. "
            f"Contact: {EMAIL}\n\n"
            + "\n".join(sections[lang] + "\n\n" + "".join(f"- [{title}](https://{DOMAIN}{path}): {description}\n"
                                                            for path, title, description in listing[lang]) for lang in ("en", "zh")))


def write(out: pathlib.Path, path: str, content: str) -> None:
    target = out / path.lstrip("/")
    if path.endswith("/"):
        target = target / "index.html"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")


def build(out: pathlib.Path) -> None:
    """Fills `out`, which must not exist yet or be empty: building never deletes anything."""
    if out.exists() and any(out.iterdir()):
        raise SystemExit(f"{out} is not empty; pass a new or empty directory")
    out.mkdir(parents=True, exist_ok=True)
    shutil.copytree(SITE / "assets", out / "assets")
    urls = {}  # path → last-modified date, for the sitemap
    listing = {lang: [] for lang in LANGS}  # (path, title, description) for llms.txt
    for name in ("index", "docs", "support", "terms"):
        slug = "" if name == "index" else f"{name}/"
        for lang in LANGS:
            meta, body = fragment(name, lang)
            path, alternate = LANGS[lang]["prefix"] + slug, LANGS[LANGS[lang]["other"]]["prefix"] + slug
            schema = app_schema(lang, meta["description"]) if name == "index" else faq_schema(body) if name == "support" else None
            write(out, path, page(lang, path, meta["title"], meta["description"], body, meta.get("nav", ""), alternate, schema=schema))
            urls[path] = updated(SITE / "pages" / f"{name}.{lang}.html")
            listing[lang].append((path, meta["title"], meta["description"]))

    for lang in LANGS:
        strings, other = LANGS[lang], LANGS[LANGS[lang]["other"]]
        entries, translated = posts(lang), {slug for slug, _, _ in posts(strings["other"])}
        path = strings["prefix"] + "blog/"
        write(out, path, page(lang, path, f'{strings["blog"]["title"]} · Roamtty', strings["blog"]["description"],
                              blog_index(lang, entries), "blog", other["prefix"] + "blog/"))
        urls[path] = updated(*(SITE / "blog" / f"{slug}.{lang}.html" for slug, _, _ in entries))
        for slug, meta, body in entries:
            path = f'{strings["prefix"]}blog/{slug}/'
            alternate = f'{other["prefix"]}blog/{slug}/' if slug in translated else None
            modified = urls[path] = max(meta["date"], updated(SITE / "blog" / f"{slug}.{lang}.html"))
            write(out, path, page(lang, path, f'{meta["title"]} · Roamtty', meta["description"], blog_post(lang, meta, body),
                                  "blog", alternate, "article", post_schema(lang, path, meta, modified)))
            listing[lang].append((path, meta["title"], meta["description"]))

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
    urls["/privacy/"] = urls["/en/privacy/"] = updated(ROOT / "docs/PRIVACY.md")
    listing["en"].append(("/en/privacy/", "Privacy Policy · Roamtty", "Roamtty Privacy Policy: the app collects no personal data."))

    meta, body = fragment("404", "zh")
    write(out, "/404.html", page("zh", "/404.html", meta["title"], meta["description"], body))

    write(out, "/sitemap.xml", '<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n'
          + "".join(f"<url><loc>https://{DOMAIN}{u}</loc><lastmod>{day}</lastmod></url>\n" for u, day in urls.items()) + "</urlset>\n")
    write(out, "/llms.txt", llms_txt(listing))
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
        # The site repository is generated wholesale: build next to the fresh clone, then swap its tree in.
        built = pathlib.Path(temp) / "built"
        build(built)
        for child in site.iterdir():
            if child.name != ".git":
                shutil.rmtree(child) if child.is_dir() else child.unlink()
        for child in built.iterdir():
            shutil.move(str(child), site / child.name)
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
