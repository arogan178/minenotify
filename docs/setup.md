# Connect coding agents to Minecraft

Use the setup assistant to connect Codex CLI, Claude Code, or both to one Minecraft instance. Install Python 3.11 or newer first. Keep the release's `tools` directory in a permanent location because the installed hooks reference `tools/notify.py`.

On Windows, replace `python3` with `py -3` in these commands.

## Find your Minecraft instance

Launch Minecraft with MineNotify installed once. Then list its configuration paths:

```sh
python3 tools/setup.py --list
```

The output contains an instance label and its config path, for example:

```text
Survival: /home/player/.local/share/PrismLauncher/instances/Survival/.minecraft/config/minenotify.json
```

The assistant searches common vanilla, Prism Launcher, and MultiMC locations on Linux, macOS, and Windows. It also searches the common Linux Flatpak launcher locations. It lists paths without opening or printing access tokens.

If your launcher stores instances elsewhere, add its directory:

```sh
python3 tools/setup.py --list --search-root "/path/to/launcher/instances"
```

Discovery stops four directory levels below each root by default. Use `--search-depth 6` for a deeper launcher layout. The maximum depth is eight. Discovery skips symlinks, game assets, worlds, mod directories, and caches. It also stops at 4,000 directories or 20,000 directory entries. If it reports a scan limit, narrow the root or pass the config path directly.

## Review the hook changes

Choose the config path for the instance you play:

```sh
python3 tools/setup.py \
  --config "/path/to/minecraft/config/minenotify.json" \
  --codex --claude
```

The assistant prints the selected Minecraft config, Python executable, bridge path, and agent settings files. It ends with `Preview only`. This command changes no files.

Use only `--codex` or only `--claude` to configure one agent. If discovery finds exactly one instance, you can omit `--config`. When discovery finds zero or multiple instances, the assistant requires an explicit `--config`.

Codex alerts cover completed responses. Claude Code alerts cover completed responses, permission prompts, idle reminders, and API errors. Ordinary ChatGPT and Claude website chats are not connected.

## Install the reviewed hooks

Close the coding agents before changing their settings. Rerun the reviewed command with `--apply`:

```sh
python3 tools/setup.py \
  --config "/path/to/minecraft/config/minenotify.json" \
  --codex --claude --apply
```

The assistant uses `~/.codex/config.toml` and `~/.claude/settings.json` by default. To select different files, add `--codex-settings "/path/to/config.toml"` or `--claude-settings "/path/to/settings.json"`. For a single Claude project, select its `.claude/settings.local.json`.

Existing settings and foreign Claude hooks remain in place. Rerunning setup updates MineNotify's hooks instead of appending duplicates. Codex's existing MineNotify hook is replaced at the top level, preserving other TOML settings and comments. Symbolic links to the same bridge count as MineNotify hooks.

If Codex already has a different `notify` command, setup refuses both requested installations before changing either settings file. Keep that notifier or remove its `notify` setting yourself before rerunning. The assistant does not chain or overwrite foreign notifiers.

Before each changed existing file, the assistant creates a backup beside that file. Its name contains `.minenotify-backup-`. The output prints the exact backup paths. Settings retain their existing file permissions. New settings files use private permissions where the operating system supports them.

The assistant validates both settings files before writing, locks them against concurrent setup runs, and replaces each file atomically. If an ordinary write fails, it restores files already changed by that run. A process or machine crash between the two replacements can leave one agent configured; keep the backups and rerun setup to finish. Settings editors and coding agents do not honor setup's locks, so keep them closed during installation.

If a crashed setup leaves a `.minenotify.lock` file, first confirm that no setup process is running. Remove only the lock path named in the error and rerun setup.

## Test the connection

Restart the selected coding agents. Enter a Minecraft world or server and run:

```sh
python3 tools/notify.py --config "/path/to/minecraft/config/minenotify.json" --test
```

You should see `Notification accepted by Minecraft.` and a notification in the game. Then finish a response in the selected coding agent to check its automatic hook.

Enable **Response preview** in Minecraft's F8 settings if you want response text. Keep `minenotify.json` private because it contains the local access token.

If you move the bridge's `tools` directory, rerun setup before deleting the old location. The assistant recognizes an existing hook by its bridge path, including symbolic links. A different Claude `notify.py` path with MineNotify's argument shape causes setup to refuse both requested installations. Inspect that handler and remove it only if it is the old MineNotify hook before installing a replacement. This avoids duplicate notifications without deleting an unrelated script's hook.

## Check setup changes during development

Run the CLI checks with temporary Minecraft configurations and agent settings:

```sh
python3 tools/test_setup.py
```

The checks install and rerun hooks, preserve foreign settings, exercise TOML comments and multiline arrays, verify backups and permissions, reject malformed inputs and foreign Codex notifiers, and forward real generated commands to a local HTTP receiver. They do not change your global agent settings. Linux verification does not establish compatibility with actual Windows or macOS launchers.
