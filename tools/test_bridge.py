#!/usr/bin/env python3
"""Exercise the bridge through its CLI and a real HTTP endpoint."""

from http.server import BaseHTTPRequestHandler, HTTPServer
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import threading
import tomllib


received = []
response_status = 202


class Receiver(BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers["Content-Length"]))
        received.append((self.path, self.headers["Authorization"], json.loads(body)))
        self.send_response(response_status)
        self.end_headers()

    def log_message(self, *_):
        pass


bridge = Path(__file__).with_name("notify.py")
with tempfile.TemporaryDirectory(prefix="minenotify-test-") as directory:
    config = Path(directory) / "config with spaces.json"
    server = HTTPServer(("127.0.0.1", 0), Receiver)
    config.write_text(json.dumps({"port": server.server_port, "token": "local-test-token"}))
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    command = [sys.executable, str(bridge), "--config", str(config)]

    def claude(raw, extra=()):
        result = subprocess.run(command + ["--claude", *extra], input=raw, capture_output=True, text=True)
        assert result.returncode == 0 and result.stdout == "", result
        if "--verbose" not in extra:
            assert result.stderr == "", result
        return result

    try:
        result = subprocess.run(command + ["--test"], capture_output=True, text=True, check=True)
        assert result.stdout == "Notification accepted by Minecraft.\n"
        assert received[-1] == (
            "/notify", "Bearer local-test-token",
            {"source": "MineNotify", "title": "Connected to Minecraft", "message": "Your local notification bridge is working."},
        )
        event = {"type": "agent-turn-complete", "cwd": "/work/my-project", "last-assistant-message": "All checks passed. ✓"}
        subprocess.run(command + [json.dumps(event)], check=True)
        assert received[-1][2] == {"source": "Codex", "title": "my-project: task finished", "message": "All checks passed. ✓"}
        subprocess.run(command + ["--message", "Build complete", "--source", "Build", "--title", "Success"], check=True, capture_output=True)
        assert received[-1][2] == {"source": "Build", "title": "Success", "message": "Build complete"}
        subprocess.run(command + ["--message", "😀" * 2048], check=True, capture_output=True)
        assert received[-1][2]["message"] == "😀" * 1024
        count = len(received)
        subprocess.run(command + [json.dumps({"type": "unrelated"})], check=True)
        assert len(received) == count
        result = subprocess.run(command + ["--print-codex-config"], capture_output=True, text=True, check=True)
        assert tomllib.loads(result.stdout)["notify"] == [sys.executable, str(bridge.resolve()), "--config", str(config.resolve())]
        invalid = subprocess.run(command + ["--message", " "], capture_output=True, text=True)
        assert invalid.returncode == 1 and "Message cannot be empty" in invalid.stderr
        claude_cases = [
            (
                {"hook_event_name": "Stop", "cwd": "/work/my-project", "last_assistant_message": "All checks passed. ✓😀", "stop_hook_active": True, "background_tasks": [{"id": "still-running"}], "session_crons": [{"id": "scheduled"}]},
                {"source": "Claude Code", "title": "my-project: Response finished", "message": "All checks passed. ✓😀"},
            ),
            (
                {"hook_event_name": "Stop"},
                {"source": "Claude Code", "title": "Response finished", "message": "Claude Code finished its response."},
            ),
            (
                {"hook_event_name": "Notification", "notification_type": "permission_prompt", "message": "Allow the build command?"},
                {"source": "Claude Code", "title": "Permission needed", "message": "Allow the build command?"},
            ),
            (
                {"hook_event_name": "Notification", "notification_type": "idle_prompt", "message": "Ready for the next task.", "cwd": "/work/project"},
                {"source": "Claude Code", "title": "Waiting for input", "message": "Ready for the next task."},
            ),
            (
                {"hook_event_name": "Notification", "notification_type": "permission_prompt", "title": "Tool approval"},
                {"source": "Claude Code", "title": "Tool approval", "message": "Claude Code is waiting for your input."},
            ),
            (
                {"hook_event_name": "StopFailure", "cwd": "/work/my-project", "error": "rate_limit", "error_details": "Please try again later.", "last_assistant_message": "The service is busy."},
                {"source": "Claude Code", "title": "my-project: Response failed", "message": "The service is busy."},
            ),
            (
                {"hook_event_name": "StopFailure", "error": "rate_limit", "error_details": "Please try again later."},
                {"source": "Claude Code", "title": "Response failed", "message": "Please try again later."},
            ),
            (
                {"hook_event_name": "StopFailure", "error": "rate_limit"},
                {"source": "Claude Code", "title": "Response failed", "message": "rate_limit"},
            ),
            (
                {"hook_event_name": "StopFailure"},
                {"source": "Claude Code", "title": "Response failed", "message": "Claude Code could not finish its response."},
            ),
            (
                {"hook_event_name": "Stop", "cwd": "/work/" + "😀" * 100, "last_assistant_message": "😀" * 2048},
                {"source": "Claude Code", "title": "😀" * 64, "message": "😀" * 1024},
            ),
            (
                {"hook_event_name": "Notification", "notification_type": "idle_prompt", "title": "a" * 127 + "😀", "message": "a" * 2047 + "😀"},
                {"source": "Claude Code", "title": "a" * 127, "message": "a" * 2047},
            ),
        ]
        for claude_event, expected in claude_cases:
            count = len(received)
            claude(json.dumps(claude_event))
            assert len(received) == count + 1
            assert received[-1] == ("/notify", "Bearer local-test-token", expected)
        ignored = [
            {"hook_event_name": "SubagentStop", "last_assistant_message": "Subagent finished."},
            {"hook_event_name": "Notification", "notification_type": "auth_success", "message": "Logged in."},
            {"hook_event_name": "Notification", "notification_type": "elicitation_dialog", "message": "Other input."},
            {},
        ]
        count = len(received)
        for claude_event in ignored:
            claude(json.dumps(claude_event))
        malformed = ["", "{", "[]", "null", "[" * 2000, " " * (1024 * 1024 + 1)]
        for field in ("hook_event_name", "cwd", "last_assistant_message", "notification_type", "message", "title", "error", "error_details"):
            malformed.append(json.dumps({"hook_event_name": "Stop", field: ["not text"]}))
        for raw in malformed:
            claude(raw)
        assert len(received) == count
        assert "must be text" in claude('{"hook_event_name":"Stop","last_assistant_message":42}', ["--verbose"]).stderr
        assert "exceeds 1 MiB" in claude(" " * (1024 * 1024 + 1), ["--verbose"]).stderr
        conflict = subprocess.run(command + ["--claude", json.dumps(event)], capture_output=True, text=True)
        assert conflict.returncode == 2 and "choose a Codex event or one action" in conflict.stderr
        conflict = subprocess.run(command + ["--claude", "--test"], capture_output=True, text=True)
        assert conflict.returncode == 2
        literal_directory = Path(directory) / "bridge 'literal' $(not-a-command)"
        literal_directory.mkdir()
        literal_bridge = literal_directory / "notify.py"
        literal_config = literal_directory / "config 'quoted' $.json"
        shutil.copyfile(bridge, literal_bridge)
        shutil.copyfile(config, literal_config)
        printed = subprocess.run([sys.executable, str(literal_bridge), "--config", str(literal_config), "--print-claude-config"], capture_output=True, text=True, check=True)
        assert printed.stderr == ""
        hooks = json.loads(printed.stdout)["hooks"]
        assert set(hooks) == {"Stop", "Notification", "StopFailure"}
        assert hooks["Notification"][0]["matcher"] == "permission_prompt|idle_prompt"
        for kind in hooks:
            handler = hooks[kind][0]["hooks"][0]
            assert handler["type"] == "command" and handler["timeout"] == 5
            claude_event = {"hook_event_name": kind, "notification_type": "permission_prompt", "last_assistant_message": "Literal paths work.", "message": "Literal paths work."}
            result = subprocess.run([handler["command"], *handler["args"]], input=json.dumps(claude_event), capture_output=True, text=True, check=True)
            assert result.stdout == "" and result.stderr == ""
            assert received[-1][2] == {
                "source": "Claude Code",
                "title": {"Stop": "Response finished", "StopFailure": "Response failed", "Notification": "Permission needed"}[kind],
                "message": "Literal paths work.",
            }
        response_status = 401
        claude(json.dumps({"hook_event_name": "Stop"}))
        assert "Access token mismatch" in claude(json.dumps({"hook_event_name": "Stop"}), ["--verbose"]).stderr
        response_status = 202
    finally:
        server.shutdown()
        server.server_close()
        thread.join()
    closed = subprocess.run(command + [json.dumps(event)], capture_output=True, text=True)
    assert closed.returncode == 0 and closed.stderr == ""
    claude(json.dumps({"hook_event_name": "Stop", "last_assistant_message": "Finished offline."}))
    assert "Cannot reach Minecraft" in claude(json.dumps({"hook_event_name": "Stop"}), ["--verbose"]).stderr
    closed_test = subprocess.run(command + ["--test"], capture_output=True, text=True)
    assert closed_test.returncode == 1 and "Cannot reach Minecraft" in closed_test.stderr
    config.write_text(json.dumps({"port": True, "token": "test"}))
    invalid = subprocess.run(command + ["--test"], capture_output=True, text=True)
    assert invalid.returncode == 1 and "invalid port" in invalid.stderr
    config.write_text("[]")
    invalid = subprocess.run(command + ["--test"], capture_output=True, text=True)
    assert invalid.returncode == 1 and "must be a JSON object" in invalid.stderr
    claude(json.dumps({"hook_event_name": "Stop"}))
    config.write_text(json.dumps({"port": 23456, "token": ""}))
    claude(json.dumps({"hook_event_name": "Stop"}))
    assert "no access token" in claude(json.dumps({"hook_event_name": "Stop"}), ["--verbose"]).stderr
print("Bridge checks passed: HTTP delivery, Codex and Claude hooks, Unicode, generated configs with literal paths, invalid input, authentication, and offline behavior.")
