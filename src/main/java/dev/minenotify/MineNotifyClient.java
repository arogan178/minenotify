package dev.minenotify;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.InputUtil;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MineNotifyClient implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("MineNotify");
    final Path configPath = FabricLoader.getInstance().getConfigDir().resolve("minenotify.json");
    final PopupHud popups = new PopupHud();
    final NotificationHistory history = new NotificationHistory();
    ConfigFile config = new ConfigFile();
    private LocalReceiver receiver;
    private String receiverStatus = "Not started";

    @Override
    public void onInitializeClient() {
        try {
            config = ConfigFile.load(configPath);
            receiver = new LocalReceiver(config.port, config.token);
            receiverStatus = "Listening on 127.0.0.1:" + receiver.port();
            LOG.info("MineNotify local receiver listening on port {}", receiver.port());
        } catch (IOException | RuntimeException e) {
            receiverStatus = "Unavailable. Check configuration or port.";
            LOG.error("MineNotify receiver could not start ({})", e.getClass().getSimpleName());
        }
        KeyBinding settingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.minenotify.settings", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F8, "category.minenotify"));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (settingsKey.wasPressed()) {
                if (client.currentScreen instanceof SettingsScreen screen) screen.close();
                else if (client.currentScreen instanceof HistoryScreen screen) screen.close();
                else client.setScreen(new SettingsScreen(client.currentScreen, this));
            }
            if (receiver != null && (client.world != null || !config.settings.enabled)) {
                Notification notification;
                while ((notification = receiver.poll()) != null) show(client, notification);
            }
        });
        HudRenderCallback.EVENT.register((context, tickCounter) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (!(client.currentScreen instanceof SettingsScreen) && !(client.currentScreen instanceof HistoryScreen) && !client.options.hudHidden) {
                popups.render(context, client.textRenderer, config.settings, context.getScaledWindowWidth(), context.getScaledWindowHeight());
            }
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> { if (receiver != null) receiver.close(); });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> dispatcher.register(
                ClientCommandManager.literal("minenotify")
                        .then(ClientCommandManager.literal("settings").executes(context -> {
                            MinecraftClient client = context.getSource().getClient();
                            client.execute(() -> client.setScreen(new SettingsScreen(client.currentScreen, this)));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("history").executes(context -> {
                            MinecraftClient client = context.getSource().getClient();
                            client.execute(() -> client.setScreen(new HistoryScreen(client.currentScreen, this)));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("clear").executes(context -> {
                            history.clear();
                            context.getSource().sendFeedback(Text.literal("MineNotify notification history cleared."));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("test").executes(context -> { test(context.getSource().getClient()); return 1; }))
                        .then(ClientCommandManager.literal("status").executes(context -> {
                            context.getSource().sendFeedback(Text.literal("MineNotify: " + status()));
                            return 1;
                        }))));
    }

    String status() { return receiverStatus + (config.settings.enabled ? " | Enabled" : " | Disabled"); }

    void test(MinecraftClient client) {
        show(client, new Notification("Codex", "Your task is finished", "Your changes are ready to review. Tests passed."));
    }

    private void show(MinecraftClient client, Notification notification) {
        Settings settings = config.settings;
        if (!settings.enabled) return;
        history.add(notification, Instant.now());
        if (settings.displayMode != Settings.DisplayMode.POPUP) {
            var text = Text.literal("[" + notification.source() + "] ").formatted(Formatting.GREEN)
                    .append(Text.literal(notification.title()).formatted(Formatting.WHITE));
            if (settings.showPreview && !notification.message().isBlank()) {
                text.append(Text.literal(" - " + notification.message()).formatted(Formatting.GRAY));
            }
            client.inGameHud.getChatHud().addMessage(text);
        }
        if (settings.displayMode != Settings.DisplayMode.CHAT) popups.add(notification, settings.durationSeconds);
        if (settings.sound) client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), 1.0f, 0.45f));
    }

    boolean save() {
        try { config.save(configPath); return true; }
        catch (IOException e) {
            LOG.error("MineNotify settings could not be saved ({})", e.getClass().getSimpleName());
            return false;
        }
    }
}
