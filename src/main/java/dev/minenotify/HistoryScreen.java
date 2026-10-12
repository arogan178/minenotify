package dev.minenotify;

import dev.minenotify.NotificationHistory.HistoryEntry;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.text.OrderedText;

public final class HistoryScreen extends Screen {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private final Screen parent;
    private final MineNotifyClient mod;
    private List<HistoryEntry> entries = List.of();
    private HistoryEntry selected;
    private int page;
    private int detailPage;
    private boolean previewsVisible;
    private List<OrderedText> detailLines = List.of();

    public HistoryScreen(Screen parent, MineNotifyClient mod) {
        super(Text.literal("Notification history"));
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
                String label = textRenderer.trimToWidth(entry.notification().title(), panel - 12);
                addDrawableChild(ButtonWidget.builder(Text.literal(label), button -> {
                    selected = entry;
                    detailPage = 0;
                    clearAndInit();
                }).dimensions(left, 61 + row * 38, panel, 20).build());
            }
        } else {
            Notification notification = selected.notification();
            String text = TIME.format(selected.receivedAt()) + " [" + notification.source() + "] " + notification.title();
            text += "\n\n" + (mod.config.settings.showPreview
                    ? (notification.message().isBlank() ? "No response preview." : notification.message())
                    : "Response previews are hidden in settings.");
            detailLines = textRenderer.wrapLines(Text.literal(text), panel - 12);
            detailPage = Math.clamp(detailPage, 0, pages() - 1);
        }
        int activePage = selected == null ? page : detailPage;
        ButtonWidget previous = addDrawableChild(ButtonWidget.builder(Text.literal("Previous"), button -> {
            if (selected == null) page--; else detailPage--;
            clearAndInit();
        }).dimensions(left, height - 56, 80, 20).build());
        previous.active = activePage > 0;
        ButtonWidget next = addDrawableChild(ButtonWidget.builder(Text.literal("Next"), button -> {
            if (selected == null) page++; else detailPage++;
            clearAndInit();
        }).dimensions(left + panel - 80, height - 56, 80, 20).build());
        next.active = activePage < pages() - 1;
        int column = (panel - 8) / 2;
        addDrawableChild(ButtonWidget.builder(Text.literal("Clear history"), button -> {
            mod.history.clear();
            selected = null;
            page = 0;
            clearAndInit();
        }).dimensions(left, height - 29, column, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(selected == null ? "Back" : "Back to history"), button -> close())
                .dimensions(left + column + 8, height - 29, column, 20).build());
    }

    @Override
    public void tick() {
        if (!entries.equals(mod.history.snapshot()) || previewsVisible != mod.config.settings.showPreview) clearAndInit();
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xf0121816);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, selected == null ? title : Text.literal("Notification details"), width / 2, 13, 0xff77dfb0);
        context.drawCenteredTextWithShadow(textRenderer, "Private to this client. Last 50. Not saved to disk.", width / 2, 29, 0xffb4c5bd);
        int panel = Math.min(500, width - 24);
        int left = (width - panel) / 2;
        if (selected == null) {
            if (entries.isEmpty()) context.drawCenteredTextWithShadow(textRenderer, "No notifications yet.", width / 2, 69, 0xffb4c5bd);
            for (int row = 0; row < rows(); row++) {
                int index = page * rows() + row;
                if (index >= entries.size()) break;
                HistoryEntry entry = entries.get(index);
                String metadata = TIME.format(entry.receivedAt()) + "  [" + entry.notification().source() + "]";
                context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(metadata, panel), left + 3, 49 + row * 38, 0xff80988b);
            }
        } else {
            for (int row = 0; row < lines(); row++) {
                int index = detailPage * lines() + row;
                if (index >= detailLines.size()) break;
                context.drawTextWithShadow(textRenderer, detailLines.get(index), left + 6, 49 + row * 12, 0xffb4c5bd);
            }
        }
        int activePage = selected == null ? page : detailPage;
        context.drawCenteredTextWithShadow(textRenderer, (activePage + 1) + " / " + pages(), width / 2, height - 50, 0xff80988b);
    }

    @Override
    public void close() {
        if (selected != null) {
            selected = null;
            clearAndInit();
        } else client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() { return false; }
}
