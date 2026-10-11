#!/usr/bin/env python3
"""Turns one e2e run into a verdict: test results from `am instrument -r`, metrics against budgets.json.

Usage: report.py <run dir> <budgets.json> <profile>
The run dir holds instrument.txt, metrics.jsonl (device + host-side samples) and device.txt.
Writes report.md and summary.json there; exits 1 when a test failed or a budget was missed.
"""
import json
import pathlib
import re
import sys

run, budgets_path, profile = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]), sys.argv[3]


def parse_instrument(text):
    """Status blocks of `am instrument -r`: code 1 starts a test, 0 passes, -2 fails, -1 errors, -3/-4 skip."""
    results, current = [], {}
    for line in text.splitlines():
        m = re.match(r"INSTRUMENTATION_STATUS: (\w+)=(.*)", line)
        if m:
            key, value = m.groups()
            if key == "stack":
                current["stack"] = value
            elif key in ("class", "test"):
                current[key] = value
            continue
        if current.get("stack") is not None and not line.startswith("INSTRUMENTATION_"):
            current["stack"] += "\n" + line
            continue
        m = re.match(r"INSTRUMENTATION_STATUS_CODE: (-?\d+)", line)
        if m:
            code = int(m.group(1))
            if code != 1:
                status = {0: "passed", -2: "failed", -1: "error", -3: "skipped", -4: "skipped"}.get(code, f"code {code}")
                stack = current.get("stack", "")
                results.append({"class": current.get("class", "?").split(".")[-1], "test": current.get("test", "?"),
                                "status": status, "message": stack.strip().splitlines()[0] if stack.strip() else ""})
            current = {}
    crashed = "INSTRUMENTATION_RESULT: shortMsg=" in text or "Process crashed" in text
    return results, crashed


instrument = (run / "instrument.txt").read_text(errors="replace") if (run / "instrument.txt").exists() else ""
tests, crashed = parse_instrument(instrument)

metrics = {}
for line in (run / "metrics.jsonl").read_text().splitlines() if (run / "metrics.jsonl").exists() else []:
    if line.strip():
        sample = json.loads(line)
        metrics[sample["name"]] = sample

budgets = json.loads(budgets_path.read_text())
checks = []
for name, spec in budgets["metrics"].items():
    limit = {k: v for k, v in {**spec, **spec.get(profile, {})}.items() if k in ("max", "min")}
    sample = metrics.get(name)
    if sample is None:
        checks.append({"name": name, "value": None, "unit": spec.get("unit", ""), "limit": limit,
                       "status": "skipped" if spec.get("optional") else "missing", "about": spec.get("about", "")})
        continue
    value = sample["value"]
    ok = ("max" not in limit or value <= limit["max"]) and ("min" not in limit or value >= limit["min"])
    checks.append({"name": name, "value": value, "unit": sample["unit"], "limit": limit,
                   "status": "ok" if ok else "over budget", "about": spec.get("about", "")})
unbudgeted = sorted(set(metrics) - set(budgets["metrics"]))

failed_tests = [t for t in tests if t["status"] in ("failed", "error")]
missed = [c for c in checks if c["status"] == "over budget"]
missing = [c for c in checks if c["status"] == "missing"]
skipped = [c for c in checks if c["status"] == "skipped"]
# A metric whose test was filtered out of this run is not a failure; one whose test ran but did not report is.
ran_classes = {t["class"] for t in tests}
missing_blocking = [c for c in missing if budgets["metrics"][c["name"]].get("source") == "host"
                    or budgets["metrics"][c["name"]].get("test", "").split("#")[0] in ran_classes]
passed = not crashed and not failed_tests and not missed and not missing_blocking and bool(tests)

device = (run / "device.txt").read_text().strip() if (run / "device.txt").exists() else ""


def fmt(value):
    return "—" if value is None else (f"{value:.1f}" if isinstance(value, float) and not value.is_integer() else f"{value:g}")


def limit_text(limit):
    parts = []
    if "max" in limit:
        parts.append(f"≤ {fmt(limit['max'])}")
    if "min" in limit:
        parts.append(f"≥ {fmt(limit['min'])}")
    return " ".join(parts)


lines = [f"# Roamtty e2e report — {'PASS' if passed else 'FAIL'}", "", device, "",
         f"Profile `{profile}` · {sum(t['status'] == 'passed' for t in tests)}/{len(tests)} tests passed"
         f" · {len(checks) - len(missed) - len(missing) - len(skipped)}/{len(checks) - len(skipped)} budgets met", ""]
if crashed:
    lines += ["**The instrumentation process crashed; see instrument.txt.**", ""]
lines += ["## Tests", "", "| Result | Test | Detail |", "|---|---|---|"]
for t in tests:
    mark = {"passed": "✅", "skipped": "⏭️"}.get(t["status"], "❌")
    lines.append(f"| {mark} {t['status']} | {t['class']}.{t['test']} | {t['message'][:160].replace('|', '/')} |")
lines += ["", "## Performance", "", "| Result | Metric | Value | Budget | What it measures |", "|---|---|---|---|---|"]
for c in checks:
    mark = {"ok": "✅", "missing": "⚪", "skipped": "⏭️"}.get(c["status"], "❌")
    lines.append(f"| {mark} {c['status']} | `{c['name']}` | {fmt(c['value'])} {c['unit']} | {limit_text(c['limit'])} | {c['about']} |")
if unbudgeted:
    lines += ["", "Recorded without a budget: " + ", ".join(f"`{n}` = {fmt(metrics[n]['value'])} {metrics[n]['unit']}" for n in unbudgeted)]
(run / "report.md").write_text("\n".join(lines) + "\n")
(run / "summary.json").write_text(json.dumps({"passed": passed, "profile": profile, "tests": tests, "budgets": checks,
                                              "metrics": metrics}, ensure_ascii=False, indent=2))
print("\n".join(lines))
sys.exit(0 if passed else 1)
