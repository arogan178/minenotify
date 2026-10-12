# Verification for release 0.1.0

Both distributable JARs build successfully. The 1.21.1 JAR uses Java 21 bytecode, and the 26.2 JAR uses Java 25 bytecode. Their metadata declares the matching Minecraft version and client-only environment.

## Automated checks

The Java core check runs against a real loopback HTTP listener. It verifies accepted delivery, exact message content, authentication failures, malformed JSON, content types, oversized bodies, queue saturation and recovery, port release on shutdown, settings persistence, config recovery, and popup placement at large offsets.

The Python bridge check runs the helper through its actual CLI against a real HTTP endpoint. It verifies Codex completion payloads, generic notifications, Unicode, long emoji text, config paths with spaces, generated TOML, ignored event types, malformed config, and behavior when Minecraft is closed.

Claude Code checks cover finished responses, permission prompts, idle reminders, and API failures. They execute the generated hook commands with paths containing spaces, quotes, and shell characters. Hook input limits, quiet failures, and optional diagnostics are checked through the CLI.

Both checks passed after the final code changes. ZIP integrity checks passed, and release archives exclude runtime configs, caches, and credentials.

## Minecraft clients

A native Minecraft 1.21.1 Fabric client received a message from the helper and displayed a popup. Its F8 settings screen opened, and the user confirmed notification delivery.

A native Minecraft 26.2 Fabric client joined a single-player world as `NotifyTest`. A message sent through the helper displayed in both local chat and the HUD popup, including response text. The game's own screenshot captured that result.

The initial 26.2 test launch failed because its test username was 18 characters, exceeding Minecraft's 16-character limit. Relaunching with `NotifyTest` resolved the login error. This was a test-launch argument error rather than a mod protocol error.

The user's pause report matched Minecraft's `pauseOnLostFocus` preference. The release adds an explicit settings button for that global preference. It does not change the preference automatically.

## Live Claude Code hook

Claude Code 2.1.296 ran a real print-mode session with temporary hook settings, default user/project settings disabled, MCP configurations disabled, and no model tools. The prompt requested the exact response `CLAUDE_BRIDGE_OK`.

Claude completed the response and invoked the generated Stop command. The actual Java `LocalReceiver` accepted a notification with source `Claude Code`, a response-finished title, and message `CLAUDE_BRIDGE_OK`. Both processes exited successfully. This tested real hook execution through the Python adapter and the mod receiver. User-level Claude settings were not edited.

Permission, idle, and failure events were tested with documented input payloads rather than real permission dialogs or induced API failures. Minecraft rendering for these new events was not rerun because the mod and its notification protocol are unchanged.

Automatic completion events from a live Codex CLI session, multiplayer-server compatibility, and automatic ChatGPT or T3 Code event capture were not exercised. Codex's documented `notify` event format is covered by the bridge check. ChatGPT and T3 Code need a separate event hook.
