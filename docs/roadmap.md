# Improvements and remaining work

## Delivered in 0.2.0

- Guided instance discovery and Codex/Claude setup with previews, backups, preservation, and repeatable updates.
- Private 50-entry notification history with pages, details, and clearing on both Minecraft versions.
- Authenticated receiver status, queue counters, and expiry of alerts waiting over five minutes.
- One release version, reproducible ZIPs, SHA-256 checksums, and automated builds and behavior checks on Linux, Windows, and macOS.

## Remaining verification

- Test actual Windows and macOS launcher installation, hook execution, and Minecraft screens. CI verifies builds and CLI behavior, which does not establish native gameplay compatibility.
- Test additional launcher layouts. Discovery currently targets vanilla, Prism Launcher, and MultiMC; explicit config paths support other launchers.

## Future features

- Automatic T3 Code or web-chat completion capture needs a supported outbound event source. The generic bridge is ready for an application that can call a local command or endpoint. Avoid scraping private session storage.
- Additional Minecraft versions need separate compatibility checks and client adapters where the game API differs.
- Public distribution and monetization remain separate decisions. Check the destination's authorship and publication rules before submission.
