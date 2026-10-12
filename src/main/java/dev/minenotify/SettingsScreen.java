package dev.minenotify;

import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.Supplier;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

public final class SettingsScreen extends Screen {
    private final Screen parent;
    private final MineNotifyClient mod;
    private String saveError = "";

    public SettingsScreen(Screen parent, MineNotifyClient mod) {
        super(Text.literal("MineNotify"));
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
        addDrawableChild(ButtonWidget.builder(Text.literal("Send test notification"), button -> {
            mod.test(client);
            button.setMessage(Text.literal(settings.enabled ? "Test sent" : "Enable notifications first"));
        }).dimensions(right, top + 96, column, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Pause in background: " + onOff(client.options.pauseOnLostFocus)), button -> {
            client.options.pauseOnLostFocus = !client.options.pauseOnLostFocus;
            client.options.write();
            button.setMessage(Text.literal("Pause in background: " + onOff(client.options.pauseOnLostFocus)));
        }).dimensions(left, top + 120, panel, 20)
                .tooltip(Tooltip.of(Text.literal("Minecraft setting for all worlds. Off keeps your singleplayer world running when you switch windows."))).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("History"), button -> {
            if (mod.save()) client.setScreen(new HistoryScreen(this, mod));
            else saveError = "Could not save settings. Check file permissions.";
        }).dimensions(left, height - 29, column, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> close())
                .dimensions(right, height - 29, column, 20).build());
    }

    private void button(int x, int y, int width, Supplier<String> label, Runnable change) {
        addDrawableChild(ButtonWidget.builder(Text.literal(label.get()), button -> {
            change.run();
            button.setMessage(Text.literal(label.get()));
        }).dimensions(x, y, width, 20).build());
    }

    private void slider(int x, int y, int width, double min, double max, double initial,
                        DoubleConsumer change, DoubleFunction<String> label, boolean integer) {
        addDrawableChild(new SliderWidget(x, y, width, 20, Text.literal(label.apply(initial)), (initial - min) / (max - min)) {
            private double actual() { double actual = min + value * (max - min); return integer ? Math.round(actual) : actual; }
            @Override protected void updateMessage() { setMessage(Text.literal(label.apply(actual()))); }
            @Override protected void applyValue() { change.accept(actual()); }
        });
    }

    private static String onOff(boolean enabled) { return enabled ? "on" : "off"; }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xf0121816);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 13, 0xff77dfb0);
        context.drawCenteredTextWithShadow(textRenderer, "Choose where task notifications appear.", width / 2, 27, 0xffb4c5bd);
        int panelBottom = Math.max(39, (height - 204) / 2) + 147;
        context.drawCenteredTextWithShadow(textRenderer, mod.status(), width / 2, panelBottom, 0xff80988b);
        if (height >= 270) context.drawCenteredTextWithShadow(textRenderer, "Setup: config/minenotify.json | F8 opens settings", width / 2, panelBottom + 13, 0xff80988b);
        if (!saveError.isBlank()) context.drawCenteredTextWithShadow(textRenderer, saveError, width / 2, height - 43, 0xffff8888);
        mod.popups.render(context, textRenderer, mod.config.settings, width, height);
    }

    @Override
    public void close() {
        if (mod.save()) client.setScreen(parent);
        else saveError = "Could not save settings. Check file permissions.";
    }

    @Override
    public boolean shouldPause() { return false; }
}
