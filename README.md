# MineNotify

Receive Codex CLI and Claude Code notifications inside Minecraft Java Edition. Choose local chat messages, popups, or both. Popups can sit in any corner with adjustable offsets, size, duration, and sound.

MineNotify runs on your client. Notifications never enter multiplayer server chat. The bridge sends messages only to your own computer and needs no API key or cloud service. Response previews are off by default.

This release has two Fabric builds. It does not support Bedrock, Forge, or NeoForge.

| Minecraft | Mod JAR | Java | Fabric Loader | Fabric API |
| --- | --- | --- | --- | --- |
| 26.2 | `minenotify-0.1.0-mc26.2.jar` | 25+ | 0.19.3+ | 0.161.0+26.2 or newer for 26.2 |
| 1.21.1 | `minenotify-0.1.0.jar` | 21+ | 0.16.14+ | A release for 1.21.1 |

## Install and test

1. Install Fabric for your Minecraft version in your launcher.
2. Put the matching MineNotify JAR and [Fabric API for your Minecraft version](https://modrinth.com/mod/fabric-api/versions) into the instance's `mods` folder. Install only one MineNotify JAR per instance.
3. Launch Minecraft and enter a world or server.
4. Press **F8** to open MineNotify settings. You can rebind this key in Minecraft's Controls menu. `/minenotify settings` opens the same screen.
5. Run `/minenotify test`. You should see a sample notification in your selected display mode.
6. Install Python 3.11 or newer for the bridge. Keep the release's `tools/notify.py` file in a permanent location.
7. Run the following command, replacing the config path with your Minecraft instance's path:

```sh
python3 tools/notify.py --config "/path/to/minecraft/config/minenotify.json" --test
```

On Windows, use `py -3` in place of `python3`. The config is created when Minecraft first starts with MineNotify. The command prints `Notification accepted by Minecraft.` when the mod accepts the message.

## Connect Codex CLI

Generate the setting for your own paths:

```sh
python3 tools/notify.py --config "/path/to/minecraft/config/minenotify.json" --print-codex-config
```

Paste the printed `notify = [...]` line at the **top level** of your user-level `~/.codex/config.toml`, before any `[section]` header. Replace an existing `notify` setting instead of adding a second one. Restart Codex CLI.

Codex appends a JSON event to the helper command when it finishes a response. This integration handles `agent-turn-complete`. It does not handle approval prompts. See [Codex notification documentation](https://learn.chatgpt.com/docs/config-file/config-advanced#notifications).

To include response text, enable response previews in MineNotify settings. Otherwise the mod displays the source and task title only. Preview text is limited to 2,048 characters before display.

The helper stays quiet when called by Codex while Minecraft is closed. Run `--test` for connection diagnostics, or add `--verbose` to the helper command for Codex-hook diagnostics.

## Connect Claude Code

Use a current Claude Code release. The hook setup uses executable-plus-arguments command hooks and is tested with version 2.1.296.

Generate the hook settings for your paths:

```sh
python3 tools/notify.py --config "/path/to/minecraft/config/minenotify.json" --print-claude-config
```

Merge the printed `hooks` object into `~/.claude/settings.json` for all projects, or `.claude/settings.local.json` for one project. If hooks already exist, append the generated entries to their event lists. Keep your other settings and hooks. Restart Claude Code.

The bridge handles finished responses, permission prompts, idle reminders, and API errors. Enable **Response preview** in F8 settings to display response or notification text. Completion alerts describe a finished response; background tasks may still be running.

Idle reminders can arrive after a completion alert. To disable them, change the generated Notification matcher to `permission_prompt`.

Claude sends hook input through stdin. The helper forwards notifications without granting permissions, changing Claude's decisions, or reading session transcripts. It stays quiet and returns success if Minecraft is closed. See the [Claude Code hook reference](https://code.claude.com/docs/en/hooks).

This integration is for Claude Code. Ordinary chats on the Claude website are not connected.

## Send notifications from other tools

Any local workflow can call the helper:

```sh
python3 tools/notify.py --config "/path/to/minecraft/config/minenotify.json" \
  --source "Build" --title "Build complete" --message "All checks passed."
```

Direct integrations can use the [local HTTP protocol](docs/protocol.md). ChatGPT's website and T3 Code are not automatically connected in this release. They need an event source that calls the helper or endpoint.

## Troubleshoot a connection

Run `/minenotify status` inside Minecraft, then rerun the bridge with `--test`.

- If the config does not exist, launch Minecraft with the mod installed. Check that your path points to the correct launcher instance.
- If the listener cannot start, close another Minecraft instance using the same port. To use multiple instances, give each a different `port` in its config while Minecraft is closed and restart.
- If the bridge reports a token mismatch, point it to the config used by the running instance.
- If the queue is full, enter a world and try again.

Settings changes are saved from the settings screen. Keep `config/minenotify.json` private because it contains the local access token. Do not include it in bug reports or releases.

If the popup says previews are off, enable **Response preview** in F8 settings. This setting controls both chat and popup response text.

Minecraft pauses single-player worlds when it loses focus by default. In the game, press **F3 + P** to toggle that behavior. MineNotify settings also include a **Pause in background** button. Turning it off changes Minecraft's global option and keeps the world running when you switch apps. MineNotify does not automatically change this preference.

## Build from source

For Minecraft 1.21.1, use Java 21:

```sh
./gradlew build
python3 tools/test_bridge.py
```

On Windows, use `gradlew.bat build` and `py -3 tools/test_bridge.py`. The installable mod is `build/libs/minenotify-0.1.0.jar`. The `-sources.jar` is for developers.

`build` runs the Java receiver checks. The Python check exercises the bridge through its command line and a real local HTTP receiver. `./gradlew runClient` launches a development client for UI checks.

For Minecraft 26.2, use Java 25:

```sh
versions/mc26.2/gradlew -p versions/mc26.2 build
```

The resulting JAR is `versions/mc26.2/build/libs/minenotify-0.1.0-mc26.2.jar`. The modern build reuses the core sources and checks from the root project. Only the Minecraft UI adapters differ.

To build with a newer compiler, set `-Pbuild_java_version=27`. The output still targets Java 25. In the development environment used here, Gradle runs under Java 21 and uses a Java 27 compiler for this target:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk versions/mc26.2/gradlew -p versions/mc26.2 -Pbuild_java_version=27 build
```

Run `python3 tools/package_release.py` after building both targets to create installation and source ZIPs in `dist/`.

## License and authorship

MIT licensed. This project was developed with substantial AI assistance. Publication on a platform is subject to that platform's current rules. See [release notes](docs/release.md).
