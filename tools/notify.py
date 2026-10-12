#!/usr/bin/env python3
"""Send a local notification to MineNotify, or adapt a Codex or Claude Code hook."""

import argparse
import json
from pathlib import Path
import sys
from urllib import error, request


def read_connection(path):
    with path.open(encoding="utf-8") as stream:
        config = json.load(stream)
    if not isinstance(config, dict):
        raise ValueError("MineNotify config must be a JSON object")
    port = config.get("port")
    token = config.get("token")
    if type(port) is not int or not 1024 <= port <= 65535:
        raise ValueError("MineNotify config has an invalid port")
    if not isinstance(token, str) or not token.strip():
        raise ValueError("MineNotify config has no access token; start Minecraft first")
    return port, token


def limited(text, units):
    return text.encode("utf-16-le")[:units * 2].decode("utf-16-le", errors="ignore")


def codex_notification(raw):
    event = json.loads(raw)
    if not isinstance(event, dict):
        raise ValueError("Codex event must be a JSON object")
    if event.get("type") != "agent-turn-complete":
        return None
    message = event.get("last-assistant-message") or "Your coding agent finished its response."
    if not isinstance(message, str):
        raise ValueError("Codex response must be text")
    cwd = event.get("cwd")
    project = Path(cwd).name if isinstance(cwd, str) else ""
    title = f"{project}: task finished" if project else "Task finished"
    return {"source": "Codex", "title": limited(title, 128), "message": limited(message, 2048)}


def claude_notification(raw):
    event = json.loads(raw)
    if not isinstance(event, dict):
        raise ValueError("Claude event must be a JSON object")
    for field in ("hook_event_name", "cwd", "last_assistant_message", "notification_type", "message", "title", "error", "error_details"):
        if field in event and not isinstance(event[field], str):
            raise ValueError(f"Claude {field} must be text")
    kind = event.get("hook_event_name")
    if kind == "Notification":
        titles = {"permission_prompt": "Permission needed", "idle_prompt": "Waiting for input"}
        title = titles.get(event.get("notification_type"))
        if title is None:
            return None
        title = event.get("title") or title
        message = event.get("message") or "Claude Code is waiting for your input."
    elif kind in ("Stop", "StopFailure"):
        project = Path(event.get("cwd", "")).name
        title = "Response finished" if kind == "Stop" else "Response failed"
        if project:
            title = f"{project}: {title}"
        message = event.get("last_assistant_message")
        if kind == "StopFailure":
            message = message or event.get("error_details") or event.get("error") or "Claude Code could not finish its response."
        else:
            message = message or "Claude Code finished its response."
    else:
        return None
    return {"source": "Claude Code", "title": limited(title, 128), "message": limited(message, 2048)}


def send(config_path, notification):
    port, token = read_connection(config_path)
    body = json.dumps(notification, ensure_ascii=False).encode("utf-8")
    req = request.Request(
        f"http://127.0.0.1:{port}/notify",
        data=body,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {token}"},
        method="POST",
    )
    opener = request.build_opener(request.ProxyHandler({}))
    with opener.open(req, timeout=2) as response:
        if response.status != 202:
            raise ValueError(f"Minecraft returned unexpected status {response.status}")


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("payload", nargs="?", help="JSON event appended by the Codex notify hook")
    result.add_argument("--config", required=True, type=Path, help="Minecraft instance's config/minenotify.json")
    actions = result.add_mutually_exclusive_group()
    actions.add_argument("--test", action="store_true", help="send a visible connection test")
    actions.add_argument("--message", help="send text from another app or script")
    actions.add_argument("--print-codex-config", action="store_true", help="print the notify setting to paste into Codex")
    actions.add_argument("--claude", action="store_true", help="read a Claude Code hook event from stdin")
    actions.add_argument("--print-claude-config", action="store_true", help="print a hooks settings fragment to merge into Claude Code")
    result.add_argument("--source", default="External", help="source label for --message")
    result.add_argument("--title", default="Notification", help="title for --message")
    result.add_argument("--verbose", action="store_true", help="also report errors when used as an agent hook")
    return result


def main(argv=None):
    cli = parser()
    args = cli.parse_args(argv)
    action = args.test or args.message is not None or args.print_codex_config or args.claude or args.print_claude_config
    if args.payload is not None and action:
        cli.error("choose a Codex event or one action")
    if args.payload is None and not action:
        cli.error("use --test, --message, --claude, --print-codex-config, --print-claude-config, or a Codex event")
    if args.print_codex_config:
        command = [sys.executable, str(Path(__file__).resolve()), "--config", str(args.config.resolve())]
        print("notify = " + json.dumps(command, ensure_ascii=False))
        return 0
    if args.print_claude_config:
        handler = {
            "type": "command",
            "command": sys.executable,
            "args": [str(Path(__file__).resolve()), "--config", str(args.config.resolve()), "--claude"],
            "timeout": 5,
        }
        hooks = {
            "Stop": [{"hooks": [handler]}],
            "Notification": [{"matcher": "permission_prompt|idle_prompt", "hooks": [handler]}],
            "StopFailure": [{"hooks": [handler]}],
        }
        print(json.dumps({"hooks": hooks}, ensure_ascii=False, indent=2))
        return 0
    is_hook = args.payload is not None or args.claude
    try:
        if args.claude:
            raw = sys.stdin.buffer.read(1024 * 1024 + 1)
            if len(raw) > 1024 * 1024:
                raise ValueError("Claude hook input exceeds 1 MiB")
            notification = claude_notification(raw)
            if notification is None:
                return 0
        elif args.payload is not None:
            notification = codex_notification(args.payload)
            if notification is None:
                return 0
        elif args.test:
            notification = {
                "source": "MineNotify",
                "title": "Connected to Minecraft",
                "message": "Your local notification bridge is working.",
            }
        else:
            if not args.message.strip():
                raise ValueError("Message cannot be empty")
            notification = {
                "source": limited(args.source, 64),
                "title": limited(args.title, 128),
                "message": limited(args.message, 2048),
            }
        send(args.config, notification)
    except (OSError, ValueError, RecursionError, error.URLError) as exc:
        if not is_hook or args.verbose:
            if isinstance(exc, error.HTTPError):
                detail = {
                    401: "Access token mismatch. Point --config to the running Minecraft instance.",
                    429: "Minecraft's notification queue is full. Enter a world and try again.",
                    400: "Minecraft rejected the notification text.",
                    413: "Notification is too large.",
                }.get(exc.code, f"Minecraft returned HTTP {exc.code}.")
            elif isinstance(exc, FileNotFoundError):
                detail = "Config not found. Launch Minecraft with MineNotify, then check --config."
            elif isinstance(exc, error.URLError):
                detail = "Cannot reach Minecraft. Check that it is running and /minenotify status reports listening."
            else:
                detail = str(exc)
            print(f"MineNotify: {detail}", file=sys.stderr)
        return 0 if is_hook else 1
    if not is_hook:
        print("Notification accepted by Minecraft.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
