package dev.minenotify;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.network.chat.Component;

public final class PopupHud {
    private record Popup(Notification notification, long expiresAt) { }
    public record Rect(int x, int y, int width, int height) { }
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
        int cardWidth = Math.min(280, screenWidth - 16);
        int cardHeight = settings.showPreview ? 82 : 51;
        int stackOffset = 0;
        context.pose().pushMatrix();
        context.pose().scale((float) settings.scale, (float) settings.scale);
        for (Popup popup : popups) {
            if (stackOffset + cardHeight > screenHeight) break;
            Rect box = layout(screenWidth, screenHeight, cardWidth, cardHeight, settings, stackOffset);
            if (box.y() < 0 || box.y() + cardHeight > screenHeight) break;
            drawCard(context, font, box, popup.notification(), settings.showPreview);
            stackOffset += cardHeight + 6;
        }
        context.pose().popMatrix();
    }

    private static void drawCard(GuiGraphicsExtractor context, Font font, Rect box, Notification message, boolean preview) {
        int x = box.x(), y = box.y(), width = box.width();
        context.fill(x + 2, y + 2, x + width + 2, y + box.height() + 2, 0x55000000);
        context.fill(x, y, x + width, y + box.height(), 0xee141b1a);
        context.fill(x, y, x + 3, y + box.height(), 0xff77dfb0);
        context.fill(x + 3, y, x + width, y + 1, 0xff3b5247);
        context.text(font, fit(font, message.source().toUpperCase(Locale.ROOT), width - 26), x + 12, y + 9, 0xff77dfb0, false);
        context.text(font, fit(font, message.title(), width - 26), x + 12, y + 25, 0xffedf6f1, false);
        if (preview) {
            List<FormattedCharSequence> lines = font.split(Component.literal(message.message().isBlank() ? "Task completed." : message.message()), width - 26);
            int count = Math.min(3, lines.size());
            for (int i = 0; i < count; i++) {
                context.text(font, lines.get(i), x + 12, y + 43 + i * 10, 0xffb4c5bd, false);
            }
            if (lines.size() > 3) {
                context.fill(x + width - 26, y + 63, x + width - 8, y + 74, 0xff141b1a);
                context.text(font, "...", x + width - 26, y + 63, 0xffb4c5bd, false);
            }
        } else {
            context.text(font, fit(font, "Enable preview in F8 settings", width - 26), x + 12, y + 38, 0xff80988b, false);
        }
    }

    private static String fit(Font font, String value, int width) {
        return font.width(value) <= width ? value : font.plainSubstrByWidth(value, Math.max(0, width - font.width("..."))) + "...";
    }
}
