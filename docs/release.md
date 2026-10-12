# Release 0.2.0

MineNotify brings external task notifications into Minecraft Java Edition 26.2 and 1.21.1 on Fabric, with a separate JAR for each version.

- Choose client chat, HUD popups, or both.
- Move popups between corners and adjust their offsets and scale.
- Set popup duration, sound, and response-preview privacy.
- Toggle Minecraft's pause-on-focus-loss preference from the settings screen.
- Connect Codex CLI through its completion-notification command.
- Connect Claude Code for finished responses, permission prompts, idle reminders, and API errors.
- Send generic notifications from local scripts.
- Inspect and clear the last 50 notifications through F8 or client commands.
- Connect agents through a setup preview that preserves existing settings.
- Query authenticated receiver health and discard queued alerts older than five minutes.
- Download reproducible installation/source ZIPs with SHA-256 checksums.

The release ZIP contains the mod JAR, Python bridge, and setup instructions. Fabric API and Python are separate prerequisites. The source ZIP contains the buildable project and verification scripts.

Upgrade both the mod JAR and Python helpers for this release. Existing MineNotify settings remain compatible. History is kept in memory and starts empty when Minecraft launches.

Both target builds run behavior checks in GitHub Actions on Linux, Windows, and macOS. Native gameplay verification covers the local Linux development client.

ChatGPT web notifications and T3 Code completion events are not captured automatically. There is no automatic publishing, analytics, or monetization integration.

## Publication

This project contains substantial AI-generated code and documentation. Disclose that accurately wherever you distribute it.

Modrinth's rules prohibit public projects primarily or entirely made from generative AI output. Its guidance says that prompting and testing alone do not qualify as substantial human authorship. An AI-written release therefore must not be presented as human-authored or assumed eligible for a public listing. Read the [Modrinth AI policy](https://support.modrinth.com/en/articles/16551575-disclosure-and-usage-of-ai) before submission.

GitHub releases provide another distribution option. The project repository is currently private. Public publication remains a separate decision.
