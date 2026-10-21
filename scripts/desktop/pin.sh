#!/usr/bin/env bash
# Pins the roamtty-desktop release this app installs: downloads its manifest.json, checks it and
# writes app/src/main/assets/desktop-plugin.json (version, download base URL, SHA-256 per target).
# Usage: scripts/desktop/pin.sh <version> [base-url]   e.g. scripts/desktop/pin.sh 0.1.0
# Upgrading the plugin only ever changes that file; moving to R2 later only changes base-url.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ver="${1:?version}"
base="${2:-https://github.com/yahaa/roamtty-desktop/releases/download/v$ver/}"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
curl -fsSL "${base%/}/manifest.json" -o "$tmp/manifest.json"
python3 -I - "$tmp/manifest.json" "$ver" "$base" "$ROOT/app/src/main/assets/desktop-plugin.json" <<'PY'
import json, re, sys
src, ver, base, out = sys.argv[1:]
m = json.load(open(src))
assert m["version"] == ver, f"manifest is {m['version']}, not {ver}"
for name, t in m["targets"].items():
    assert re.fullmatch(r"[0-9a-f]{64}", t["sha256"]), name
    assert re.fullmatch(r"[A-Za-z0-9._-]+", t["file"]), name
m["baseUrl"] = base if base.endswith("/") else base + "/"
json.dump(m, open(out, "w"), indent=2, sort_keys=True)
open(out, "a").write("\n")
print(f"pinned roamtty-desktop {ver}: {', '.join(sorted(m['targets']))}")
PY
