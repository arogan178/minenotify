#!/usr/bin/env python3
"""Exercise the setup CLI against temporary Minecraft and agent settings."""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import threading
import tomllib
import unittest


SETUP = Path(__file__).with_name("setup.py").resolve()
HELPER = SETUP.with_name("notify.py")


class SetupTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="minenotify setup ")
        self.addCleanup(self.temporary.cleanup)
        self.home = Path(self.temporary.name).resolve()
        self.config = self.home / "custom instance/config/minenotify.json"
        self.config.parent.mkdir(parents=True)
        self.config.write_text(json.dumps({"port": 28765, "token": "private-test-token"}), encoding="utf-8")
        self.codex = self.home / ".codex/config.toml"
        self.claude = self.home / ".claude/settings.json"
        self.environment = {**os.environ, "HOME": str(self.home), "USERPROFILE": str(self.home),
                            "APPDATA": str(self.home / "roaming"), "LOCALAPPDATA": str(self.home / "local"),
                            "XDG_DATA_HOME": str(self.home / "data")}

    def run_setup(self, *args, success=True):
        result = subprocess.run([sys.executable, str(SETUP), *map(str, args)], env=self.environment,
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 0 if success else 1, result.stdout + result.stderr)
        self.assertNotIn("private-test-token", result.stdout + result.stderr)
        return result

    def write(self, path, text):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def install(self, *extra):
        return self.run_setup("--config", self.config, "--codex", "--claude", "--apply", *extra)

    def test_preview_is_read_only_and_install_is_idempotent(self):
        preview = self.run_setup("--config", self.config, "--codex", "--claude")
        self.assertIn("Preview only", preview.stdout)
        self.assertFalse(self.codex.exists())
        self.assertFalse(self.claude.exists())
        self.install()
        command = tomllib.loads(self.codex.read_text())["notify"]
        self.assertEqual(command, [sys.executable, str(HELPER), "--config", str(self.config)])
        installed = self.claude.read_bytes()
        result = self.install()
        self.assertIn(f"Unchanged: {self.claude}", result.stdout)
        self.assertEqual(self.claude.read_bytes(), installed)
        self.assertEqual(list(self.home.rglob("*.minenotify-backup-*")), [])

    def test_preserves_foreign_claude_hooks_and_settings(self):
        foreign = {"type": "command", "command": "echo", "args": ["user hook"]}
        source = {"model": "custom-model", "hooks": {"Stop": [{"matcher": "*", "hooks": [foreign]}],
                                                   "SessionStart": [{"hooks": [foreign]}]}}
        original = json.dumps(source)
        self.write(self.claude, original)
        self.install()
        settings = json.loads(self.claude.read_text())
        self.assertEqual(settings["model"], "custom-model")
        self.assertEqual(settings["hooks"]["SessionStart"], source["hooks"]["SessionStart"])
        self.assertEqual(settings["hooks"]["Stop"][0], source["hooks"]["Stop"][0])
        self.assertEqual(len(settings["hooks"]["Stop"]), 2)
        backups = list(self.claude.parent.glob("settings.json.minenotify-backup-*"))
        self.assertEqual(len(backups), 1)
        self.assertEqual(backups[0].read_text(), original)

    def test_foreign_notify_refuses_both_without_any_mutation(self):
        original = 'notify = ["my-existing-notifier"]\n[model]\nname = "mine"\n'
        self.write(self.codex, original)
        self.write(self.claude, '{"model":"mine"}')
        result = self.run_setup("--config", self.config, "--codex", "--claude", "--apply", success=False)
        self.assertIn("foreign notify", result.stderr)
        self.assertEqual(self.codex.read_text(), original)
        self.assertEqual(self.claude.read_text(), '{"model":"mine"}')
        self.assertEqual(list(self.home.rglob("*.minenotify-*")), [])

    def test_toml_multiline_notify_preserves_comments_and_tables(self):
        old_command = [sys.executable, str(HELPER), "--config", "/old instance/config/minenotify.json"]
        original = '# top comment\n"notify" = [\n' + "\n".join(json.dumps(word) + "," for word in old_command) + '\n# internal comment\n] # trailing comment\n[profiles.work]\nmodel = "custom"\nnotify = ["table-value"]\n'
        self.write(self.codex, original)
        self.run_setup("--config", self.config, "--codex", "--apply")
        updated = self.codex.read_text()
        for comment in ("# top comment", "# internal comment", "# trailing comment"):
            self.assertIn(comment, updated)
        settings = tomllib.loads(updated)
        self.assertEqual(settings["notify"][-1], str(self.config))
        self.assertEqual(settings["profiles"]["work"], {"model": "custom", "notify": ["table-value"]})

    def test_absent_notify_is_prepended_before_tables(self):
        original = '# user settings\n[profiles.work]\nnotify = ["foreign-table-setting"]\nmodel = "mine"\n'
        self.write(self.codex, original)
        self.run_setup("--config", self.config, "--codex", "--apply")
        updated = self.codex.read_text()
        self.assertTrue(updated.endswith(original))
        self.assertEqual(tomllib.loads(updated)["profiles"]["work"]["notify"], ["foreign-table-setting"])

    def test_toml_triple_quoted_value_preserves_trailing_comment(self):
        words = [sys.executable, str(HELPER), "--config"]
        original = "notify = [" + ", ".join(json.dumps(word) for word in words) + ', """old #path""""] # keep this comment\n'
        self.write(self.codex, original)
        self.run_setup("--config", self.config, "--codex", "--apply")
        updated = self.codex.read_text()
        self.assertIn("# keep this comment", updated)
        self.assertNotIn("#path", updated)
        self.assertEqual(tomllib.loads(updated)["notify"][-1], str(self.config))

    def test_updates_symlink_owned_handlers_preserving_shared_entry(self):
        alias = self.home / "old bridge.py"
        try:
            alias.symlink_to(HELPER)
        except OSError:
            self.skipTest("Symbolic links unavailable")
        owned = {"type": "command", "command": sys.executable,
                 "args": [str(alias), "--config", "/old-config", "--claude"]}
        foreign = {"type": "command", "command": "other", "args": []}
        self.write(self.claude, json.dumps({"hooks": {"Stop": [{"hooks": [owned, foreign]}]}}))
        self.run_setup("--config", self.config, "--claude", "--apply")
        entries = json.loads(self.claude.read_text())["hooks"]["Stop"]
        self.assertEqual(entries[0]["hooks"], [foreign])
        self.assertEqual(entries[1]["hooks"][0]["args"][0], str(HELPER))
        self.assertEqual(len(entries), 2)

    def test_possible_stale_claude_hook_refuses_both_without_mutation(self):
        old = {"type": "command", "command": sys.executable,
               "args": [str(self.home / "old tools/notify.py"), "--config", "/old-config", "--claude"]}
        original = json.dumps({"hooks": {"Stop": [{"hooks": [old]}]}})
        self.write(self.claude, original)
        result = self.run_setup("--config", self.config, "--codex", "--claude", "--apply", success=False)
        self.assertIn("different bridge path", result.stderr)
        self.assertEqual(self.claude.read_text(), original)
        self.assertFalse(self.codex.exists())
        self.assertEqual(list(self.home.rglob("*.minenotify-*")), [])

    def test_crlf_codex_tables_remain_byte_identical(self):
        original = b'# user settings\r\n[profiles.work]\r\nmodel = "mine"\r\n'
        self.codex.parent.mkdir()
        self.codex.write_bytes(original)
        self.run_setup("--config", self.config, "--codex", "--apply")
        self.assertTrue(self.codex.read_bytes().endswith(original))
        self.assertEqual(tomllib.loads(self.codex.read_text())["profiles"]["work"]["model"], "mine")

    def test_malformed_settings_preflight_leaves_codex_unchanged(self):
        self.write(self.codex, 'model = "custom"\n')
        self.write(self.claude, '{"hooks": {"Stop": "wrong"}}')
        self.run_setup("--config", self.config, "--codex", "--claude", "--apply", success=False)
        self.assertEqual(self.codex.read_text(), 'model = "custom"\n')
        self.assertEqual(list(self.home.rglob("*.minenotify-*")), [])
        self.write(self.claude, '{"broken":')
        self.run_setup("--config", self.config, "--codex", "--claude", "--apply", success=False)
        self.assertEqual(self.codex.read_text(), 'model = "custom"\n')

    def test_invalid_config_never_installs_hooks(self):
        for contents in ('{"port":true,"token":"private-test-token"}', '{"port":28765,"token":""}', '{"broken":'):
            self.config.write_text(contents)
            self.run_setup("--config", self.config, "--codex", "--apply", success=False)
            self.assertFalse(self.codex.exists())

    def test_preserves_permissions_and_explicit_settings_paths(self):
        target = self.home / "agent settings/custom.toml"
        original = 'model = "mine"\n'
        self.write(target, original)
        target.chmod(0o640)
        self.run_setup("--config", self.config, "--codex", "--codex-settings", target, "--apply")
        self.assertFalse(self.codex.exists())
        self.assertEqual(stat.S_IMODE(target.stat().st_mode), 0o640 if os.name != "nt" else 0o666)
        backups = list(target.parent.glob("custom.toml.minenotify-backup-*"))
        self.assertEqual(backups[0].read_text(), original)
        self.assertEqual(stat.S_IMODE(backups[0].stat().st_mode), stat.S_IMODE(target.stat().st_mode))

    def test_discovery_ambiguity_depth_and_symlink_exclusion(self):
        first = self.home / ".minecraft/config/minenotify.json"
        second = self.home / "data/PrismLauncher/instances/Survival/.minecraft/config/minenotify.json"
        self.write(first, self.config.read_text())
        listing = self.run_setup("--list")
        self.assertIn(str(first), listing.stdout)
        self.run_setup("--codex")
        self.write(second, self.config.read_text())
        listing = self.run_setup("--list")
        self.assertIn(f"Survival: {second}", listing.stdout)
        self.run_setup("--codex", "--apply", success=False)
        self.assertFalse(self.codex.exists())
        extra = self.home / "launcher instances"
        third = extra / "One/Two/Three/config/minenotify.json"
        self.write(third, self.config.read_text())
        self.assertNotIn(str(third), self.run_setup("--list", "--search-root", extra, "--search-depth", 2).stdout)
        self.assertIn(str(third), self.run_setup("--list", "--search-root", extra, "--search-depth", 3).stdout)
        try:
            (extra / "loop").symlink_to(extra, target_is_directory=True)
        except OSError:
            return
        self.assertIn(str(third), self.run_setup("--list", "--search-root", extra).stdout)

    def test_lock_refuses_without_changing_existing_settings(self):
        self.write(self.codex, 'model = "mine"\n')
        lock = self.codex.with_name(self.codex.name + ".minenotify.lock")
        lock.write_text("12345\n")
        result = self.run_setup("--config", self.config, "--codex", "--apply", success=False)
        self.assertIn("Setup is locked", result.stderr)
        self.assertEqual(self.codex.read_text(), 'model = "mine"\n')
        self.assertEqual(lock.read_text(), "12345\n")
        self.assertEqual(list(self.codex.parent.glob("*.minenotify-backup-*")), [])

    def test_generated_hooks_forward_real_events(self):
        received = []

        class Receiver(BaseHTTPRequestHandler):
            def do_POST(self):
                received.append((self.path, self.headers["Authorization"], json.loads(self.rfile.read(int(self.headers["Content-Length"])))))
                self.send_response(202)
                self.end_headers()

            def log_message(self, *args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), Receiver)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        self.config.write_text(json.dumps({"port": server.server_port, "token": "private-test-token"}))
        self.install()
        command = tomllib.loads(self.codex.read_text())["notify"]
        codex = {"type": "agent-turn-complete", "cwd": "/work/project", "last-assistant-message": "Codex result"}
        subprocess.run([*command, json.dumps(codex)], check=True, capture_output=True, timeout=10)
        handler = json.loads(self.claude.read_text())["hooks"]["Stop"][0]["hooks"][0]
        claude = {"hook_event_name": "Stop", "cwd": "/work/project", "last_assistant_message": "Claude result"}
        subprocess.run([handler["command"], *handler["args"]], input=json.dumps(claude), text=True,
                       check=True, capture_output=True, timeout=10)
        self.assertEqual(received, [
            ("/notify", "Bearer private-test-token", {"source": "Codex", "title": "project: task finished", "message": "Codex result"}),
            ("/notify", "Bearer private-test-token", {"source": "Claude Code", "title": "project: Response finished", "message": "Claude result"})])


if __name__ == "__main__":
    unittest.main(verbosity=2)
