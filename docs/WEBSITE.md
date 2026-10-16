# 官网 roamtty.com

官网为 Google Play 与 App Store 上架提供所需的公开网址，并介绍 Android 版与 iOS 版。源码在本仓库 `site/`，由 `scripts/site.py` 生成静态页面，推送到公开仓库 `yahaa/roamtty-site`，GitHub Pages 以自定义域名 `roamtty.com` 提供服务，DNS 与 HTTPS 经 Cloudflare。本仓库保持私有。

## 商店需要填写的网址

| 用途 | Google Play Console | App Store Connect | 网址 |
| --- | --- | --- | --- |
| 隐私政策 | 应用内容 → 隐私权政策（必填） | App 隐私 → 隐私政策网址（必填） | `https://roamtty.com/privacy/`（中英双语；英文单独页 `/en/privacy/`） |
| 技术支持 | 商店详情 → 网站（可选） | 支持网址（必填，须含联系方式） | `https://roamtty.com/support/`；英文本地化填 `/en/support/` |
| 营销网址 | — | 营销网址（可选） | `https://roamtty.com/`；英文 `/en/` |
| 使用条款 | — | 可选；未提供自定义 EULA 时适用 Apple 标准 EULA | `https://roamtty.com/terms/` |
| 联系邮箱 | 开发者联系邮箱（必填，商店展示） | 审核联系信息 | `support@roamtty.com` |
| 账号删除 | 应用无账号，数据安全表单中选“不收集” | 应用无账号，无需提供删除入口 | 支持页“如何删除我的数据” |

App Store 的“App 隐私”问卷选择“不收集数据”；iOS 的 `ITSAppUsesNonExemptEncryption` 为 `true`（使用 SSH 标准加密），出口合规按 App Store Connect 问卷填写。

## 页面

| 路径 | 内容 | 来源 |
| --- | --- | --- |
| `/`、`/en/` | 首页：功能、平台、隐私摘要、商店按钮 | `site/pages/index.{zh,en}.html` |
| `/docs/`、`/en/docs/` | 使用文档：准备服务器、添加主机、会话与 AI Agent、终端操作、文件、监控、后台，以及 Tailscale / 自建 Headscale / DERP 组网 | `site/pages/docs.{zh,en}.html` |
| `/support/`、`/en/support/` | 联系方式、反馈须知、系统要求、常见问题、安全问题报告 | `site/pages/support.{zh,en}.html` |
| `/terms/`、`/en/terms/` | 使用条款 | `site/pages/terms.{zh,en}.html` |
| `/privacy/`、`/en/privacy/` | 隐私政策 | `docs/PRIVACY.md`（与 Android 应用内显示的是同一份） |
| `/404.html`、`/robots.txt`、`/sitemap.xml`、`/.well-known/security.txt` | 自动生成 | `scripts/site.py` |

页面片段开头的 `<!-- title: … -->`、`<!-- description: … -->`、`<!-- nav: … -->` 是页面元数据；`{{stores}}` 替换为商店按钮，`{{email}}` 替换为支持邮箱。版式、导航和页脚在 `scripts/site.py` 的 `page()` 中，样式在 `site/assets/style.css`。网站不引用任何外部字体、脚本或统计代码，在中国大陆也能完整加载。

应用上架后，把商店链接填入 `scripts/site.py` 的 `PLAY_URL`、`APP_STORE_URL`，按钮即从“即将上架”变为下载链接。修改隐私政策后同步更新两种语言的生效日期并重新发布。

## 构建与发布

```sh
scripts/site.py --out /tmp/roamtty-site        # 仅构建（目录须不存在或为空，脚本不会删除任何文件）
python3 -m http.server 8000 -d /tmp/roamtty-site # 本地预览 http://127.0.0.1:8000/
scripts/site.py                                 # 构建并推送到 yahaa/roamtty-site
```

需要已登录的 `gh`（隐私政策的 Markdown 通过 GitHub API 渲染，发布时克隆并推送站点仓库）。站点仓库的内容整体由脚本生成，不要手工修改。本机未配置 git 身份时，用 `GIT_AUTHOR_NAME`、`GIT_AUTHOR_EMAIL`、`GIT_COMMITTER_NAME`、`GIT_COMMITTER_EMAIL` 环境变量提供。推送后 GitHub Pages 通常一分钟内完成部署。

## GitHub Pages 与 Cloudflare 配置

当前状态（2026-10-16 核对）：`yahaa/roamtty-site` 从 `main` 分支根目录发布，自定义域名 `roamtty.com`；域名的 NS 在 Cloudflare，`roamtty.com` 与 `www.roamtty.com` 均开启代理，`www` 301 跳转到主域名。

重新配置或迁移时按以下步骤：

1. **GitHub**：站点仓库 Settings → Pages，Source 选 `Deploy from a branch`、`main` / `/ (root)`，Custom domain 填 `roamtty.com`（与仓库中的 `CNAME` 文件一致）。建议在账号 Settings → Pages 中验证 `roamtty.com`，防止域名被他人仓库占用（需在 Cloudflare 添加 GitHub 给出的 `_github-pages-challenge-yahaa` TXT 记录）。
2. **Cloudflare DNS**：
   - `roamtty.com`：4 条 A 记录 `185.199.108.153`、`185.199.109.153`、`185.199.110.153`、`185.199.111.153`（可再加 AAAA `2606:50c0:8000::153` 至 `8003::153`）；
   - `www`：CNAME 到 `yahaa.github.io`。
   - 首次配置时先设为“仅 DNS”（灰色云），等 GitHub 签发证书、勾选 Enforce HTTPS 后，再改为“已代理”（橙色云）。
3. **Cloudflare SSL/TLS**：加密模式选 **Full (strict)**（GitHub Pages 有有效证书）；不要用 Flexible，否则会与 GitHub 的 HTTPS 跳转形成重定向循环。开启“始终使用 HTTPS”。
4. **www 跳转**（可选）：Rules → Redirect Rules，将 `www.roamtty.com/*` 301 到 `https://roamtty.com/${1}`；GitHub Pages 本身也会把 `www` 跳到主域名。
5. **缓存**：更新隐私政策后如需立即生效，在 Cloudflare Caching → Configuration 中清除缓存。

## 支持邮箱

`support@roamtty.com` 写在网站、隐私政策和 `security.txt` 中，商店审核会用它联系开发者。域名目前没有 MX 记录，需在 Cloudflare **Email → Email Routing** 中启用：Cloudflare 自动添加 MX 与 SPF 记录，再创建自定义地址 `support@roamtty.com` 转发到开发者的个人邮箱，并在该邮箱中确认验证邮件。启用后发一封测试邮件确认能收到。

使用文档中 Headscale 的版本号与命令按写作时的最新版（0.29.5）核对过；Headscale 升级改动 CLI 或配置项时，同步更新两种语言的文档。应用界面文字变化时，文档里引用的按钮名称和错误提示也要随之更新（中文以 `tr()` 原文为准，英文以 `AppLocale.kt` 的译文为准）。

首页截图在 `site/assets/screens/`，是 Android 应用连接演示服务器时的实际界面；重新截图的方法见 `scripts/site-demo/README.md`。应用界面有明显变化时同步更新截图。
