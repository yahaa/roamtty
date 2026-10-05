"""Registers or removes the roamtty-notify hooks of Claude Code, Codex and Grok.

    python3 - install|uninstall <notify script path>

Only the user's own config files change; each is backed up once to *.roamtty.bak before the first
edit. Entries are recognized by the script path, so installing twice adds nothing and uninstalling
removes only Roamtty's handlers. Prints one __ITERMUX_NOTIFY_CLI__=<cli> <status> line per CLI.
"""
import json
import os
import shlex
import shutil
import sys

MODE, SCRIPT = sys.argv[1], sys.argv[2]
HOME = os.path.expanduser("~")
STATE = os.path.join(HOME, ".roamtty")
MARK = "/.roamtty/bin/roamtty-notify"


def command(*args):
    return " ".join(shlex.quote(part) for part in (SCRIPT,) + args)


def handler(*args):
    return {"type": "command", "command": command(*args), "timeout": 5}


def claude_hooks(grok=False):
    return {
        "Notification": [{"matcher": "permission_prompt" if grok else
                          "permission_prompt|elicitation_dialog|elicitation_url_dialog|agent_needs_input",
                          "hooks": [handler("attention")]}],
        "PreToolUse": [{"matcher": "AskUserQuestion|ask_user_question" if grok else "AskUserQuestion",
                        "hooks": [handler("attention")]}],
        "PostToolUse": [{"hooks": [handler("resolved")]}],
        "UserPromptSubmit": [{"hooks": [handler("resolved")]}],
        "Stop": [{"hooks": [handler("resolved")]}],
    }


CODEX_HOOKS = {
    "PermissionRequest": [{"hooks": [handler("attention", "codex")]}],
    "PostToolUse": [{"hooks": [handler("resolved", "codex")]}],
    "Stop": [{"hooks": [handler("resolved", "codex")]}],
}


def report(cli, status):
    print("__ITERMUX_NOTIFY_CLI__=%s %s" % (cli, status))
    sys.stdout.flush()


def present(cli, folder):
    return os.path.isdir(os.path.join(HOME, folder)) or shutil.which(cli) is not None


def created_marker(path):
    return os.path.join(STATE, "created", path.replace("/", "%"))


def load(path):
    """The parsed object, {} for a missing or empty file, or None when it is not a JSON object."""
    if not os.path.exists(path):
        return {}
    try:
        with open(path, encoding="utf-8") as source:
            text = source.read()
        value = json.loads(text) if text.strip() else {}
    except (OSError, ValueError):
        return None
    return value if isinstance(value, dict) else None


def save(path, value):
    folder = os.path.dirname(path)
    os.makedirs(folder, exist_ok=True)
    if os.path.exists(path) and not os.path.exists(path + ".roamtty.bak"):
        shutil.copy2(path, path + ".roamtty.bak")
    temporary = path + ".roamtty.tmp"
    with open(temporary, "w", encoding="utf-8") as target:
        json.dump(value, target, indent=2, ensure_ascii=False)
        target.write("\n")
    if os.path.exists(path):
        shutil.copymode(path, temporary)
    os.replace(temporary, path)


def ours(entry):
    return isinstance(entry, dict) and MARK in str(entry.get("command", ""))


def strip(config):
    """Removes Roamtty's handlers; returns whether anything changed."""
    hooks = config.get("hooks")
    if not isinstance(hooks, dict):
        return False
    changed = False
    for event in list(hooks):
        groups = hooks[event]
        if not isinstance(groups, list):
            continue
        kept = []
        for group in groups:
            inner = group.get("hooks") if isinstance(group, dict) else None
            if isinstance(inner, list) and any(ours(entry) for entry in inner):
                changed = True
                inner = [entry for entry in inner if not ours(entry)]
                if not inner:
                    continue
                group = dict(group, hooks=inner)
            kept.append(group)
        if kept:
            hooks[event] = kept
        else:
            del hooks[event]
    if changed and not hooks:
        del config["hooks"]
    return changed


def merge(path, wanted):
    config = load(path)
    if config is None:
        return "invalid"
    if "hooks" in config and not isinstance(config["hooks"], dict):
        return "invalid"
    if not os.path.exists(path):
        os.makedirs(os.path.join(STATE, "created"), exist_ok=True)
        open(created_marker(path), "w").close()
    strip(config)
    hooks = config.setdefault("hooks", {})
    for event, groups in wanted.items():
        current = hooks.get(event)
        hooks[event] = (current if isinstance(current, list) else []) + groups
    save(path, config)
    return "ok"


def unmerge(path):
    config = load(path)
    if config is None or not os.path.exists(path):
        return
    if strip(config):
        if not config and os.path.exists(created_marker(path)):
            os.remove(path)
        else:
            save(path, config)


claude = os.path.join(HOME, ".claude", "settings.json")
codex = os.path.join(HOME, ".codex", "hooks.json")
grok = os.path.join(HOME, ".grok", "hooks", "roamtty.json")

if MODE == "install":
    for cli, folder, path, wanted in (("claude", ".claude", claude, claude_hooks()),
                                      ("codex", ".codex", codex, CODEX_HOOKS)):
        report(cli, merge(path, wanted) if present(cli, folder) else "absent")
    if present("grok", ".grok"):
        # A whole file of its own; Grok drops handlers identical to the ones it reads from Claude's settings.
        save(grok, {"hooks": claude_hooks(grok=True)})
        report("grok", "ok")
    else:
        report("grok", "absent")
else:
    unmerge(claude)
    unmerge(codex)
    if os.path.exists(grok):
        os.remove(grok)
    report("all", "removed")
