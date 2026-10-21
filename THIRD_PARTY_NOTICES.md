# Third-party notices

Roamtty's original code is copyright 2026 Roamtty contributors, licensed under Apache-2.0. See [LICENSE](LICENSE).

| Component | Pinned version / source | License |
| --- | --- | --- |
| Termux terminal-emulator and terminal-view | `termux/termux-app@4584488513c099f2e98bcfcd00f006d213248729` | Apache-2.0 exception declared in upstream LICENSE.md; original copyright notices retained |
| mwiede/JSch | 2.28.0 | BSD-3-Clause; embedded JZlib BSD and jBCrypt ISC notices included |
| Bouncy Castle bcprov-jdk18on | 1.80 | MIT-style Bouncy Castle license |
| Linux Tux icon | `devicons/devicon@7330accdbc47e2dc0c19789a48533c4a3c50fe58`, rendered from `icons/linux/linux-original.svg` at 192 px | Tux originally created by Larry Ewing with GIMP; Devicon vector copyright (c) 2015 konpa, MIT. Full text in `licenses/Devicon-MIT.txt` |
| Linux distribution icons | `devicons/devicon@7330accdbc47e2dc0c19789a48533c4a3c50fe58`, rendered from `icons/{name}/{name}-original.svg` at 192 px; names: `fedora`, `archlinux`, `redhat`, `opensuse`, `rockylinux`, `almalinux`, `gentoo`, `nixos`, `linuxmint` | Devicon vector copyright (c) 2015 konpa, MIT. Full text in `licenses/Devicon-MIT.txt` |
| Ubuntu Circle of Friends, round | Official Ubuntu community brand asset, linked from [Ubuntu merchandise assets](https://discourse.ubuntu.com/t/ubuntu-merchandise-assets/67314); original SVG and hash in `artwork/host-icons/` | © Canonical Ltd.; Ubuntu and the Circle of Friends are Canonical trademarks. [IP policy](https://canonical.com/legal/intellectual-property-policy). Used only to identify remote Ubuntu systems. |
| Debian open-use swirl | [Official unlabelled logo](https://www.debian.org/logos/openlogo-nd.svg); original SVG and hash in `artwork/host-icons/` | © 1999 Software in the Public Interest, Inc.; designed by Raul Silva. CC BY-SA 3.0, `licenses/CC-BY-SA-3.0.txt`. Rendered to PNG without changing the artwork. |
| CentOS symbol | `CentOS/artwork/centos-brand@8ef4ff3ac2ffe0f84df4eb775329cca5b26b8626`, `Sources/centos-symbol.svg`; original SVG in `artwork/host-icons/` | © 2022 Alain Reguera Delgado. CC BY-SA 4.0, `licenses/CC-BY-SA-4.0.txt`; [CentOS trademark guidelines](https://www.centos.org/legal/trademarks/). Rendered to PNG without changing the artwork. |
| Windows and macOS icons | `devicons/devicon@7330accdbc47e2dc0c19789a48533c4a3c50fe58`, vector paths from `icons/windows11/windows11-original.svg` and `icons/apple/apple-original.svg`; Apple silhouette recolored with rainbow bands | Devicon vector copyright (c) 2015 konpa, MIT. Full text in `licenses/Devicon-MIT.txt` |
| Kotlin and kotlinx.coroutines | 2.1.21 / 1.10.2 | Apache-2.0 |
| AndroidX / Jetpack Compose / Room | Exact versions in Gradle build files | Apache-2.0 |
| Gradle Wrapper | 8.11.1 | Apache-2.0 |
| JUnit / Hamcrest (tests only) | 4.13.2 / transitive | EPL-1.0 / BSD-3-Clause |
| Bundled terminal fonts (JetBrains Mono 2.304, Fira Code 6.2, Source Code Pro 2.042, IBM Plex Mono 1.1.0, Cascadia Mono 2404.23) | assets/fonts + licenses/fonts | SIL Open Font License 1.1 |
| Terminal symbol fallback: Noto Sans Symbols 2 (2.008) | assets/fonts + licenses/fonts | SIL Open Font License 1.1 |
| Terminal icon fallback: Symbols Nerd Font Mono (Nerd Fonts 3.5.1) | assets/fonts + licenses/fonts | MIT |

The remote desktop plugin `roamtty-desktop` is a separate Apache-2.0 project (github.com/yahaa/roamtty-desktop) with its own THIRD_PARTY_NOTICES; it is not bundled in the app, which only pins its version and SHA-256. Its software H.264 encoder is Cisco's OpenH264 binary, downloaded at install time from ciscobinary.openh264.org so Cisco's patent licence covers it; it is never built or bundled by Roamtty. The app decodes H.264 with the system MediaCodec.

Upstream Termux is GPLv3 as an application. Only the two terminal libraries covered by its explicit Apache-2.0 exception are vendored. No Termux app, termux-shared, local shell launcher, JNI source, or native Termux binary is shipped. The original repository license declaration is preserved at `licenses/Termux-upstream-LICENSE.md`.

Local changes to the vendored libraries:

- Replaced the local-process TerminalSession implementation with a transport-neutral interface.
- Added a silent client adapter; application parser diagnostics do not log terminal text.
- Enabled text-class IME composition and prevented uncommitted-composition deletion from sending remote Backspace.
- Enqueued bracketed paste as one packet; exposed the current paste mode to the UI.
- Disabled remote OSC 52 clipboard writes. Local selection and copying remain available.
- Added SSH-specific regression tests. Source files retain upstream headers.
- TerminalRenderer: disable subpixel text AA and enable glyph hinting for clearer mobile LCD/OLED rendering.

Full license texts are in `licenses/` and LICENSE, and are bundled in the application's offline guide. Dependencies used only to build or test are not part of the release APK. The separately downloaded Codex Linux binary is used only to validate a real remote AI terminal tool and is not shipped in the app or source archive.

Sources: https://github.com/termux/termux-app/blob/4584488513c099f2e98bcfcd00f006d213248729/LICENSE.md, https://github.com/mwiede/jsch/tree/jsch-2.28.0, https://github.com/bcgit/bc-java/tree/r1rv80, https://github.com/devicons/devicon/blob/7330accdbc47e2dc0c19789a48533c4a3c50fe58/icons/linux/linux-original.svg, https://github.com/devicons/devicon/blob/7330accdbc47e2dc0c19789a48533c4a3c50fe58/icons/windows11/windows11-original.svg, https://github.com/devicons/devicon/blob/7330accdbc47e2dc0c19789a48533c4a3c50fe58/icons/apple/apple-original.svg, https://www.linuxfoundation.org/legal/the-linux-mark.
