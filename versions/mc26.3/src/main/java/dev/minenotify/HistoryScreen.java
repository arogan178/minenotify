package dev.minenotify;

import dev.minenotify.NotificationHistory.HistoryEntry;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

public final class HistoryScreen extends Screen {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private final Screen parent;
    private final MineNotifyClient mod;
    private List<HistoryEntry> entries = List.of();
    private HistoryEntry selected;
    private int page;
    private int detailPage;
    private boolean previewsVisible;
    private List<FormattedCharSequence> detailLines = List.of();

    public HistoryScreen(Screen parent, MineNotifyClient mod) {
        super(Component.literal("Notification history"));
        this.parent = parent;
        this.mod = mod;
    }

    private int rows() { return Math.max(1, Math.min(6, (height - 106) / 38)); }
    private int lines() { return Math.max(1, (height - 106) / 12); }
    private int pages() {
        int count = selected == null ? entries.size() : detailLines.size();
        int perPage = selected == null ? rows() : lines();
        return Math.max(1, (count + perPage - 1) / perPage);
    }

    @Override
    protected void init() {
        entries = mod.history.snapshot();
        previewsVisible = mod.config.settings.showPreview;
        if (selected != null && !entries.contains(selected)) selected = null;
        int panel = Math.min(500, width - 24);
        int left = (width - panel) / 2;
        if (selected == null) {
            detailLines = List.of();
            page = Math.clamp(page, 0, pages() - 1);
            for (int row = 0; row < rows(); row++) {
                int index = page * rows() + row;
                if (index >= entries.size()) break;
                HistoryEntry entry = entries.get(index);
                String label = font.plainSubstrByWidth(entry.notification().title(), panel - 12);
                addRenderableWidget(Button.builder(Component.literal(label), button -> {
                    selected = entry;
                    detailPage = 0;
                    rebuildWidgets();
                }).bounds(left, 61 + row * 38, panel, 20).build());
            }
        } else {
            Notification notification = selected.notification();
            String text = TIME.format(selected.receivedAt()) + " [" + notification.source() + "] " + notification.title();
            text += "\n\n" + (mod.config.settings.showPreview
                    ? (notification.message().isBlank() ? "No response preview." : notification.message())
                    : "Response previews are hidden in settings.");
            detailLines = font.split(Component.literal(text), panel - 12);
            detailPage = Math.clamp(detailPage, 0, pages() - 1);
        }
        int activePage = selected == null ? page : detailPage;
        Button previous = addRenderableWidget(Button.builder(Component.literal("Previous"), button -> {
            if (selected == null) page--; else detailPage--;
            rebuildWidgets();
        }).bounds(left, height - 56, 80, 20).build());
        previous.active = activePage > 0;
        Button next = addRenderableWidget(Button.builder(Component.literal("Next"), button -> {
            if (selected == null) page++; else detailPage++;
            rebuildWidgets();
        }).bounds(left + panel - 80, height - 56, 80, 20).build());
        next.active = activePage < pages() - 1;
        int column = (panel - 8) / 2;
        addRenderableWidget(Button.builder(Component.literal("Clear history"), button -> {
            mod.history.clear();
            selected = null;
            page = 0;
            rebuildWidgets();
        }).bounds(left, height - 29, column, 20).build());
        addRenderableWidget(Button.builder(Component.literal(selected == null ? "Back" : "Back to history"), button -> onClose())
                .bounds(left + column + 8, height - 29, column, 20).build());
    }

    @Override
    public void tick() {
        if (!entries.equals(mod.history.snapshot()) || previewsVisible != mod.config.settings.showPreview) rebuildWidgets();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xf0121816);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
        context.centeredText(font, selected == null ? title : Component.literal("Notification details"), width / 2, 13, 0xff77dfb0);
        context.centeredText(font, "Private to this client. Last 50. Not saved to disk.", width / 2, 29, 0xffb4c5bd);
        int panel = Math.min(500, width - 24);
        int left = (width - panel) / 2;
        if (selected == null) {
            if (entries.isEmpty()) context.centeredText(font, "No notifications yet.", width / 2, 69, 0xffb4c5bd);
            for (int row = 0; row < rows(); row++) {
                int index = page * rows() + row;
                if (index >= entries.size()) break;
                HistoryEntry entry = entries.get(index);
                String metadata = TIME.format(entry.receivedAt()) + "  [" + entry.notification().source() + "]";
                context.text(font, font.plainSubstrByWidth(metadata, panel), left + 3, 49 + row * 38, 0xff80988b, true);
            }
        } else {
            for (int row = 0; row < lines(); row++) {
                int index = detailPage * lines() + row;
                if (index >= detailLines.size()) break;
                context.text(font, detailLines.get(index), left + 6, 49 + row * 12, 0xffb4c5bd, true);
            }
        }
        int activePage = selected == null ? page : detailPage;
        context.centeredText(font, (activePage + 1) + " / " + pages(), width / 2, height - 50, 0xff80988b);
    }

    @Override
    public void onClose() {
        if (selected != null) {
            selected = null;
            rebuildWidgets();
        } else minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
