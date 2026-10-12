package dev.minenotify;

import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

public final class SettingsScreen extends Screen {
    private final Screen parent;
    private final MineNotifyClient mod;
    private String saveError = "";

    public SettingsScreen(Screen parent, MineNotifyClient mod) {
        super(Component.literal("MineNotify"));
        this.parent = parent;
        this.mod = mod;
    }

    @Override
    protected void init() {
        Settings settings = mod.config.settings;
        int panel = Math.min(420, width - 24);
        int column = (panel - 8) / 2;
        int left = (width - panel) / 2;
        int right = left + column + 8;
        int top = Math.max(39, (height - 204) / 2);
        button(left, top, column, () -> "Notifications: " + onOff(settings.enabled), () -> settings.enabled = !settings.enabled);
        button(right, top, column, () -> "Sound: " + onOff(settings.sound), () -> settings.sound = !settings.sound);
        button(left, top + 24, column, () -> "Display: " + settings.displayMode.name().toLowerCase(Locale.ROOT),
                () -> settings.displayMode = Settings.DisplayMode.values()[(settings.displayMode.ordinal() + 1) % 3]);
        button(right, top + 24, column, () -> "Position: " + settings.position.label(),
                () -> settings.position = Settings.Position.values()[(settings.position.ordinal() + 1) % 4]);
        button(left, top + 48, column, () -> "Response preview: " + onOff(settings.showPreview), () -> settings.showPreview = !settings.showPreview);
        slider(right, top + 48, column, 2, 30, settings.durationSeconds,
                value -> settings.durationSeconds = (int) value, value -> "Duration: " + (int) value + " seconds", true);
        slider(left, top + 72, column, 0.75, 1.5, settings.scale,
                value -> settings.scale = value, value -> String.format(Locale.ROOT, "Popup scale: %.0f%%", value * 100), false);
        slider(right, top + 72, column, 0, 160, settings.offsetX,
                value -> settings.offsetX = (int) value, value -> "Horizontal inset: " + (int) value, true);
        slider(left, top + 96, column, 0, 160, settings.offsetY,
                value -> settings.offsetY = (int) value, value -> "Vertical inset: " + (int) value, true);
        addRenderableWidget(Button.builder(Component.literal("Send test notification"), button -> {
            mod.test(minecraft);
            button.setMessage(Component.literal(settings.enabled ? "Test sent" : "Enable notifications first"));
        }).bounds(right, top + 96, column, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Pause in background: " + onOff(minecraft.options.pauseOnLostFocus)), button -> {
            minecraft.options.pauseOnLostFocus = !minecraft.options.pauseOnLostFocus;
            minecraft.options.save();
            button.setMessage(Component.literal("Pause in background: " + onOff(minecraft.options.pauseOnLostFocus)));
        }).bounds(left, top + 120, panel, 20)
                .tooltip(Tooltip.create(Component.literal("Minecraft setting for all worlds. Off keeps your singleplayer world running when you switch windows."))).build());
        addRenderableWidget(Button.builder(Component.literal("History"), button -> {
            if (mod.save()) minecraft.gui.setScreen(new HistoryScreen(this, mod));
            else saveError = "Could not save settings. Check file permissions.";
        }).bounds(left, height - 29, column, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                .bounds(right, height - 29, column, 20).build());
    }

    private void button(int x, int y, int width, Supplier<String> label, Runnable change) {
        addRenderableWidget(Button.builder(Component.literal(label.get()), button -> {
            change.run();
            button.setMessage(Component.literal(label.get()));
        }).bounds(x, y, width, 20).build());
    }

    private void slider(int x, int y, int width, double min, double max, double initial,
                        DoubleConsumer change, DoubleFunction<String> label, boolean integer) {
        addRenderableWidget(new AbstractSliderButton(x, y, width, 20, Component.literal(label.apply(initial)), (initial - min) / (max - min)) {
            private double actual() { double actual = min + value * (max - min); return integer ? Math.round(actual) : actual; }
            @Override protected void updateMessage() { setMessage(Component.literal(label.apply(actual()))); }
            @Override protected void applyValue() { change.accept(actual()); }
        });
    }

    private static String onOff(boolean enabled) { return enabled ? "on" : "off"; }

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xf0121816);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
        context.centeredText(font, title, width / 2, 13, 0xff77dfb0);
        context.centeredText(font, "Choose where task notifications appear.", width / 2, 27, 0xffb4c5bd);
        int panelBottom = Math.max(39, (height - 204) / 2) + 147;
        context.centeredText(font, mod.status(), width / 2, panelBottom, 0xff80988b);
        if (height >= 270) context.centeredText(font, "Setup: config/minenotify.json | F8 opens settings", width / 2, panelBottom + 13, 0xff80988b);
        if (!saveError.isBlank()) context.centeredText(font, saveError, width / 2, height - 43, 0xffff8888);
        mod.popups.render(context, font, mod.config.settings, width, height);
    }

    @Override
    public void onClose() {
        if (mod.save()) minecraft.gui.setScreen(parent);
        else saveError = "Could not save settings. Check file permissions.";
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
