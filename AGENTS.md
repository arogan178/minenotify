# MineNotify development

- For non-trivial engineering work, read `~/.agents/skills/poteto-mode/SKILL.md` when available. Follow the user's session instructions for model selection and delegation.
- Shared notification, configuration, and receiver code lives in `src/main/java/dev/minenotify`. Minecraft UI adapters exist there and under `versions/mc26.2/src/main/java/dev/minenotify`. Update both adapters when changing user-visible behavior.
- Network code never calls Minecraft APIs. Client ticks own display state. Keep notification queues and history bounded.
- Notifications are private client messages. Never send them to server chat. Response previews are optional; do not persist response bodies by default.
- Run `python3 tools/test_bridge.py` after bridge changes. Run the corresponding setup checks after installer changes.
- Build 1.21.1 with Java 21 and `./gradlew build`. Build 26.2 with Java 25 and `versions/mc26.2/gradlew -p versions/mc26.2 build`. If Gradle runs under Java 21 with a Java 27 compiler installed, pass `-Pbuild_java_version=27` to the modern build.
- Keep runtime configs, tokens, worlds, caches, and generated release ZIPs out of Git. Generated archives must use an explicit source-file list.
- Verify real behavior and report the limits of verification. Do not claim automatic ChatGPT or T3 Code forwarding unless its event source is connected and tested.
