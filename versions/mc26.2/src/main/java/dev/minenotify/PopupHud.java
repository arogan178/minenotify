package dev.minenotify;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.network.chat.Component;

public final class PopupHud {
    private record Popup(Notification notification, long expiresAt) { }
    public record Rect(int x, int y, int width, int height) { }
    private static final int TOOLTIP_BACKGROUND = 0xf0100010;
    // Vanilla toasts are 160 wide; text starts after the 16px icon.
    private static final int MIN_CARD_WIDTH = 160, MAX_TEXT_WIDTH = 182, TEXT_INSET = 38;
    private static final String PREVIEW_HINT = "Enable preview in F8 settings";
    private final List<Popup> popups = new ArrayList<>();

    public void add(Notification notification, int seconds) {
        popups.addFirst(new Popup(notification, System.nanoTime() + seconds * 1_000_000_000L));
        if (popups.size() > 3) popups.removeLast();
    }

    public static Rect layout(int screenWidth, int screenHeight, int cardWidth, int cardHeight,
                              Settings settings, int stackOffset) {
        int x = settings.position.right ? screenWidth - settings.offsetX - cardWidth : settings.offsetX;
        int baseY = settings.position.bottom ? screenHeight - settings.offsetY - cardHeight : settings.offsetY;
        int y = Math.clamp(baseY, 0, Math.max(0, screenHeight - cardHeight))
                + (settings.position.bottom ? -stackOffset : stackOffset);
        return new Rect(Math.clamp(x, 0, Math.max(0, screenWidth - cardWidth)),
                y, cardWidth, cardHeight);
    }

    public void render(GuiGraphicsExtractor context, Font font, Settings settings, int width, int height) {
        popups.removeIf(popup -> System.nanoTime() >= popup.expiresAt());
        if (!settings.enabled || settings.displayMode == Settings.DisplayMode.CHAT || popups.isEmpty()) return;
        int screenWidth = (int) (width / settings.scale);
        int screenHeight = (int) (height / settings.scale);
        int maxTextWidth = Math.min(MAX_TEXT_WIDTH, screenWidth - 16 - TEXT_INSET);
        int stackOffset = 0;
        context.pose().pushMatrix();
        context.pose().scale((float) settings.scale, (float) settings.scale);
        for (Popup popup : popups) {
            Notification message = popup.notification();
            List<FormattedCharSequence> lines = settings.showPreview
                    ? font.split(Component.literal(message.message().isBlank() ? "Task completed." : message.message()), maxTextWidth)
                    : List.of();
            int textWidth = Math.max(font.width(message.source()), font.width(message.title()));
            for (FormattedCharSequence line : lines) textWidth = Math.max(textWidth, font.width(line));
            if (!settings.showPreview) textWidth = Math.max(textWidth, font.width(PREVIEW_HINT));
            int cardWidth = TEXT_INSET + Math.min(Math.max(textWidth, MIN_CARD_WIDTH - TEXT_INSET), maxTextWidth);
            int cardHeight = settings.showPreview ? 40 + Math.min(3, lines.size()) * 10 : 48;
            if (stackOffset + cardHeight > screenHeight) break;
            Rect box = layout(screenWidth, screenHeight, cardWidth, cardHeight, settings, stackOffset);
            if (box.y() < 0 || box.y() + cardHeight > screenHeight) break;
            drawCard(context, font, box, message, lines, settings.showPreview);
            stackOffset += cardHeight + 6;
        }
        context.pose().popMatrix();
    }

    private static void drawCard(GuiGraphicsExtractor context, Font font, Rect box, Notification message,
                                 List<FormattedCharSequence> lines, boolean preview) {
        int x = box.x(), y = box.y(), width = box.width(), height = box.height();
        drawTooltipFrame(context, x, y, width, height);
        context.item(new ItemStack(Items.BELL), x + 8, y + 8);
        int textX = x + 30, textWidth = width - TEXT_INSET;
        context.text(font, fit(font, message.source(), textWidth), textX, y + 8, 0xffffff55, true);
        context.text(font, fit(font, message.title(), textWidth), textX, y + 20, 0xffffffff, true);
        if (preview) {
            int count = Math.min(3, lines.size());
            for (int i = 0; i < count; i++) {
                context.text(font, lines.get(i), textX, y + 34 + i * 10, 0xffaaaaaa, true);
            }
            if (lines.size() > 3) {
                context.fill(x + width - 26, y + 53, x + width - 8, y + 63, TOOLTIP_BACKGROUND);
                context.text(font, "...", x + width - 26, y + 54, 0xffaaaaaa, true);
            }
        } else {
            context.text(font, fit(font, PREVIEW_HINT, textWidth), textX, y + 34, 0xff808080, true);
        }
    }

    // Vanilla's pre-1.21.2 item tooltip: notched dark-purple panel with a purple gradient border.
    private static void drawTooltipFrame(GuiGraphicsExtractor context, int x, int y, int width, int height) {
        int top = 0x505000ff, bottom = 0x5028007f;
        context.fill(x + 1, y, x + width - 1, y + height, TOOLTIP_BACKGROUND);
        context.fill(x, y + 1, x + 1, y + height - 1, TOOLTIP_BACKGROUND);
        context.fill(x + width - 1, y + 1, x + width, y + height - 1, TOOLTIP_BACKGROUND);
        context.fillGradient(x + 1, y + 1, x + 2, y + height - 1, top, bottom);
        context.fillGradient(x + width - 2, y + 1, x + width - 1, y + height - 1, top, bottom);
        context.fill(x + 2, y + 1, x + width - 2, y + 2, top);
        context.fill(x + 2, y + height - 2, x + width - 2, y + height - 1, bottom);
    }

    private static String fit(Font font, String value, int width) {
        return font.width(value) <= width ? value : font.plainSubstrByWidth(value, Math.max(0, width - font.width("..."))) + "...";
    }
}
