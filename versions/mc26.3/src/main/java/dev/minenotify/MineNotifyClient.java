package dev.minenotify;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.Identifier;
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
        KeyMapping settingsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.minenotify.settings", InputConstants.Type.KEYBOARD, InputConstants.KEY_F8, KeyMapping.Category.register(Identifier.fromNamespaceAndPath("minenotify", "notifications"))));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (settingsKey.consumeClick()) {
                if (client.gui.screen() instanceof SettingsScreen screen) screen.onClose();
                else if (client.gui.screen() instanceof HistoryScreen screen) screen.onClose();
                else client.gui.setScreen(new SettingsScreen(client.gui.screen(), this));
            }
            if (receiver != null && (client.level != null || !config.settings.enabled)) {
                Notification notification;
                while ((notification = receiver.poll()) != null) show(client, notification);
            }
        });
        HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath("minenotify", "popups"), (context, tickCounter) -> {
            Minecraft client = Minecraft.getInstance();
            if (!(client.gui.screen() instanceof SettingsScreen) && !(client.gui.screen() instanceof HistoryScreen) && !client.gui.hud.isHidden()) {
                popups.render(context, client.font, config.settings, context.guiWidth(), context.guiHeight());
            }
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> { if (receiver != null) receiver.close(); });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> dispatcher.register(
                ClientCommands.literal("minenotify")
                        .then(ClientCommands.literal("settings").executes(context -> {
                            Minecraft client = context.getSource().getClient();
                            client.execute(() -> client.gui.setScreen(new SettingsScreen(client.gui.screen(), this)));
                            return 1;
                        }))
                        .then(ClientCommands.literal("history").executes(context -> {
                            Minecraft client = context.getSource().getClient();
                            client.execute(() -> client.gui.setScreen(new HistoryScreen(client.gui.screen(), this)));
                            return 1;
                        }))
                        .then(ClientCommands.literal("clear").executes(context -> {
                            history.clear();
                            context.getSource().sendFeedback(Component.literal("MineNotify notification history cleared."));
                            return 1;
                        }))
                        .then(ClientCommands.literal("test").executes(context -> { test(context.getSource().getClient()); return 1; }))
                        .then(ClientCommands.literal("status").executes(context -> {
                            context.getSource().sendFeedback(Component.literal("MineNotify: " + status()));
                            return 1;
                        }))));
    }

    String status() { return receiverStatus + (config.settings.enabled ? " | Enabled" : " | Disabled"); }

    void test(Minecraft client) {
        show(client, new Notification("Codex", "Your task is finished", "Your changes are ready to review. Tests passed."));
    }

    private void show(Minecraft client, Notification notification) {
        Settings settings = config.settings;
        if (!settings.enabled) return;
        history.add(notification, Instant.now());
        if (settings.displayMode != Settings.DisplayMode.POPUP) {
            var text = Component.literal("[" + notification.source() + "] ").withStyle(ChatFormatting.GREEN)
                    .append(Component.literal(notification.title()).withStyle(ChatFormatting.WHITE));
            if (settings.showPreview && !notification.message().isBlank()) {
                text.append(Component.literal(" - " + notification.message()).withStyle(ChatFormatting.GRAY));
            }
            client.gui.hud.getChat().addClientSystemMessage(text);
        }
        if (settings.displayMode != Settings.DisplayMode.CHAT) popups.add(notification, settings.durationSeconds);
        if (settings.sound) client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_CHIME.value(), 1.0f, 0.45f));
    }

    boolean save() {
        try { config.save(configPath); return true; }
        catch (IOException e) {
            LOG.error("MineNotify settings could not be saved ({})", e.getClass().getSimpleName());
            return false;
        }
    }
}
