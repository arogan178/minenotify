#!/usr/bin/env python3
"""Discover MineNotify instances and preview or install local agent hooks."""

import argparse
from contextlib import ExitStack, contextmanager
from dataclasses import dataclass
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile
import tomllib

from notify import read_connection


HELPER = Path(__file__).with_name("notify.py").resolve()
EXCLUDED = {"mods", "saves", "assets", "libraries", "logs", "screenshots", ".git", "node_modules", "cache", "caches"}


@dataclass
class Edit:
    path: Path
    original: bytes | None
    updated: bytes
    mode: int


def discover(roots, depth):
    found = set()
    visited = 0
    inspected = 0
    pending = [(root, 0) for root in roots if root.is_dir() and not root.is_symlink()]
    while pending:
        directory, level = pending.pop()
        visited += 1
        if visited > 4000:
            print("Discovery stopped at 4,000 directories. Use a narrower --search-root.", file=sys.stderr)
            break
        candidate = directory / "config" / "minenotify.json"
        if not candidate.parent.is_symlink() and candidate.is_file() and not candidate.is_symlink():
            found.add(candidate.resolve())
        if level >= depth:
            continue
        try:
            with os.scandir(directory) as entries:
                for entry in entries:
                    inspected += 1
                    if inspected > 20000 or visited + len(pending) >= 4000:
                        print("Discovery reached its scan limit. Use a narrower --search-root.", file=sys.stderr)
                        return sorted(found)
                    if entry.name not in EXCLUDED and entry.name != "config" and entry.is_dir(follow_symlinks=False):
                        pending.append((Path(entry.path), level + 1))
        except OSError:
            continue
    return sorted(found)


def launcher_roots(home):
    appdata = Path(os.environ.get("APPDATA", home / "AppData/Roaming"))
    localdata = Path(os.environ.get("LOCALAPPDATA", home / "AppData/Local"))
    data = Path(os.environ.get("XDG_DATA_HOME", home / ".local/share"))
    roots = [home / ".minecraft", appdata / ".minecraft", home / "Library/Application Support/minecraft"]
    for launcher in ("PrismLauncher", "MultiMC"):
        roots.extend(base / launcher / "instances" for base in (data, appdata, localdata, home / "Library/Application Support"))
        roots.append(home / launcher / "instances")
    roots.extend(home / ".var/app" / package / "data" / launcher / "instances" for package, launcher in (
        ("org.prismlauncher.PrismLauncher", "PrismLauncher"), ("org.multimc.MultiMC", "MultiMC")))
    return list(dict.fromkeys(roots))


def owned_command(command, args=None):
    if not isinstance(command, (str, list)) or args is not None and not isinstance(args, list):
        return False
    words = command if isinstance(command, list) else [command, *(args or [])]
    if not all(isinstance(word, str) for word in words):
        return False
    return (len(words) >= 4 and Path(words[1]).resolve() == HELPER
            and words[2] == "--config" and bool(words[3]))


def toml_comments(text):
    comments = []
    index = 0
    quote = None
    while index < len(text):
        if quote is not None:
            if quote.startswith('"') and text[index] == "\\":
                index += 2
                continue
            if text.startswith(quote, index):
                index += len(quote)
                if len(quote) == 3:
                    while index < len(text) and text[index] == quote[0]:
                        index += 1
                quote = None
                continue
        elif text[index] in "\"'":
            character = text[index]
            quote = character * 3 if text.startswith(character * 3, index) else character
            index += len(quote)
            continue
        elif text[index] == "#":
            end = text.find("\n", index)
            end = len(text) if end < 0 else end
            comments.append(text[index:end].rstrip("\r"))
            index = end
        index += 1
    return "".join(comment + "\n" for comment in comments)


def generated(config, flag):
    result = subprocess.run([sys.executable, str(HELPER), "--config", str(config), flag],
                            capture_output=True, text=True, check=True)
    return result.stdout


def codex_settings(text, fragment):
    settings = tomllib.loads(text)
    if "notify" not in settings:
        return fragment.rstrip() + "\n" + text
    if not owned_command(settings["notify"]):
        raise ValueError("Codex already has a foreign notify command. Keep it, or remove it yourself before installing MineNotify; no files changed.")
    lines = text.splitlines(keepends=True)
    for start, line in enumerate(lines):
        if not re.match(r"^\s*(?:notify|'notify'|\"notify\")\s*=", line):
            continue
        try:
            prefix = tomllib.loads("".join(lines[:start]))
        except tomllib.TOMLDecodeError:
            continue
        if "notify" in prefix:
            continue
        for end in range(start + 1, len(lines) + 1):
            try:
                partial = tomllib.loads("".join(lines[:end]))
            except tomllib.TOMLDecodeError:
                continue
            if partial.get("notify") == settings["notify"]:
                suffix = lines[end:]
                replacement = toml_comments("".join(lines[start:end])) + fragment.rstrip() + "\n"
                updated = "".join(lines[:start]) + replacement + "".join(suffix)
                if tomllib.loads(updated).get("notify") == tomllib.loads(fragment)["notify"]:
                    return updated
                break
    raise ValueError("Cannot safely locate the top-level Codex notify setting. Use a plain notify = [...] assignment.")


def claude_settings(text, fragment):
    settings = json.loads(text) if text.strip() else {}
    if not isinstance(settings, dict):
        raise ValueError("Claude settings must be a JSON object")
    hooks = settings.setdefault("hooks", {})
    if not isinstance(hooks, dict):
        raise ValueError("Claude hooks must be an object")
    additions = json.loads(fragment)["hooks"]
    for event, new_entries in additions.items():
        entries = hooks.setdefault(event, [])
        if not isinstance(entries, list):
            raise ValueError(f"Claude {event} hooks must be an array")
        kept = []
        for entry in entries:
            if not isinstance(entry, dict) or not isinstance(entry.get("hooks"), list):
                raise ValueError(f"Claude {event} has a malformed hook entry")
            handlers = entry["hooks"]
            if not all(isinstance(handler, dict) for handler in handlers):
                raise ValueError(f"Claude {event} has a malformed hook handler")
            for handler in handlers:
                args = handler.get("args")
                if (handler.get("type") == "command" and isinstance(args, list) and len(args) >= 4
                        and all(isinstance(word, str) for word in args) and Path(args[0]).name == "notify.py"
                        and args[1] == "--config" and args[3] == "--claude"
                        and not owned_command(handler.get("command"), args)):
                    raise ValueError(f"Claude {event} has a possible MineNotify hook at a different bridge path. Inspect that handler and remove it only if it is the old MineNotify hook before installing; no files changed.")
            remaining = [handler for handler in handlers if not (handler.get("type") == "command"
                         and owned_command(handler.get("command"), handler.get("args")))]
            if remaining or not handlers:
                kept.append({**entry, "hooks": remaining})
        hooks[event] = kept + new_entries
    return json.dumps(settings, ensure_ascii=False, indent=2) + "\n"


def make_plan(config, targets):
    edits = []
    for path, flag, transform in targets:
        path = path.expanduser().resolve()
        original = path.read_bytes() if path.exists() else None
        mode = stat.S_IMODE(path.stat().st_mode) if original is not None else 0o600
        text = original.decode("utf-8") if original is not None else ""
        updated = transform(text, generated(config, flag)).encode("utf-8")
        edits.append(Edit(path, original, updated, mode))
    if len({edit.path for edit in edits}) != len(edits):
        raise ValueError("Codex and Claude settings must use different files")
    return edits


def atomic_write(path, content, mode):
    descriptor, temporary = tempfile.mkstemp(prefix=".minenotify-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(temporary, mode)
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)


@contextmanager
def lock(path):
    path.parent.mkdir(parents=True, exist_ok=True)
    lock_path = path.with_name(path.name + ".minenotify.lock")
    try:
        descriptor = os.open(lock_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        raise ValueError(f"Setup is locked for {path}. Close other setup processes. If one crashed, remove {lock_path} before retrying.") from None
    try:
        with os.fdopen(descriptor, "w") as stream:
            stream.write(str(os.getpid()) + "\n")
        yield
    finally:
        lock_path.unlink(missing_ok=True)


def install(config, targets):
    make_plan(config, targets)
    with ExitStack() as stack:
        for path in sorted({target[0].expanduser().resolve() for target in targets}):
            stack.enter_context(lock(path))
        edits = make_plan(config, targets)
        changed = [edit for edit in edits if edit.original != edit.updated]
        backups = []
        written = []
        try:
            for edit in changed:
                if edit.original is not None:
                    descriptor, filename = tempfile.mkstemp(prefix=edit.path.name + ".minenotify-backup-", dir=edit.path.parent)
                    os.close(descriptor)
                    backup = Path(filename)
                    atomic_write(backup, edit.original, edit.mode)
                    backups.append(backup)
            for edit in changed:
                current = edit.path.read_bytes() if edit.path.exists() else None
                if current != edit.original:
                    raise ValueError(f"Settings changed during setup: {edit.path}. Close the agent and retry.")
                atomic_write(edit.path, edit.updated, edit.mode)
                written.append(edit)
        except BaseException:
            for edit in reversed(written):
                try:
                    if edit.original is None:
                        edit.path.unlink(missing_ok=True)
                    else:
                        atomic_write(edit.path, edit.original, edit.mode)
                except OSError as exc:
                    print(f"Restore failed for {edit.path}: {exc}. Restore its backup manually.", file=sys.stderr)
            for backup in backups:
                print(f"Backup retained: {backup}", file=sys.stderr)
            raise
    return edits, backups


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--list", action="store_true", help="list discovered instances without showing access tokens")
    parser.add_argument("--search-root", action="append", type=Path, default=[], help="also search this launcher directory")
    parser.add_argument("--search-depth", type=int, choices=range(0, 9), default=4, metavar="0..8")
    parser.add_argument("--config", type=Path, help="select an instance's config/minenotify.json")
    parser.add_argument("--codex", action="store_true", help="configure Codex CLI response notifications")
    parser.add_argument("--claude", action="store_true", help="configure Claude Code response and attention notifications")
    parser.add_argument("--codex-settings", type=Path, default=Path.home() / ".codex/config.toml")
    parser.add_argument("--claude-settings", type=Path, default=Path.home() / ".claude/settings.json")
    parser.add_argument("--apply", action="store_true", help="write the displayed plan with backups")
    args = parser.parse_args(argv)
    try:
        configs = discover(launcher_roots(Path.home()) + [root.expanduser() for root in args.search_root], args.search_depth) if args.list or args.config is None else []
        if args.list:
            for config in configs:
                instance = config.parent.parent
                vanilla = {Path.home() / ".minecraft", Path(os.environ.get("APPDATA", Path.home() / "AppData/Roaming")) / ".minecraft",
                           Path.home() / "Library/Application Support/minecraft"}
                label = "Vanilla" if instance in {path.resolve() for path in vanilla} else instance.parent.name if instance.name == ".minecraft" else instance.name
                print(f"{label}: {config}")
            if not configs:
                print("No MineNotify instances found. Start Minecraft with the mod, or add --search-root.")
            if not args.codex and not args.claude:
                if args.apply:
                    parser.error("--apply requires --codex or --claude")
                return 0
        if not args.codex and not args.claude:
            parser.error("choose --list, --codex, --claude, or both agent flags")
        if args.config is None:
            if len(configs) != 1:
                raise ValueError("Select an instance with --config. Run --list to see available paths; setup does not guess when there are zero or multiple instances.")
            config = configs[0]
        else:
            config = args.config.expanduser().resolve()
        read_connection(config)
        targets = []
        if args.codex:
            targets.append((args.codex_settings, "--print-codex-config", codex_settings))
        if args.claude:
            targets.append((args.claude_settings, "--print-claude-config", claude_settings))
        edits = make_plan(config, targets)
        print(f"Minecraft config: {config}")
        print(f"Bridge: {HELPER}")
        print(f"Python: {sys.executable}")
        for edit in edits:
            action = "Unchanged" if edit.original == edit.updated else "Update" if edit.original is not None else "Create"
            print(f"{action}: {edit.path}")
        print("Codex: completed responses." if args.codex else "Claude Code: completed responses, permission prompts, idle reminders, and API errors.")
        if args.codex and args.claude:
            print("Claude Code: completed responses, permission prompts, idle reminders, and API errors.")
        if not args.apply:
            print("Preview only. Rerun with --apply to install these hooks. No files changed.")
            return 0
        edits, backups = install(config, targets)
        for backup in backups:
            print(f"Backup: {backup}")
        print("Hooks installed. Restart the selected coding agents. Keep tools/notify.py in this location.")
        return 0
    except (OSError, ValueError, subprocess.CalledProcessError) as exc:
        print(f"MineNotify setup: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
