package dev.minenotify;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;

public final class NotificationHistory {
    public static final int LIMIT = 50;
    public record HistoryEntry(Notification notification, Instant receivedAt) {
        public HistoryEntry {
            Objects.requireNonNull(notification);
            Objects.requireNonNull(receivedAt);
        }
    }

    private final ArrayDeque<HistoryEntry> entries = new ArrayDeque<>();

    public void add(Notification notification, Instant receivedAt) {
        entries.addFirst(new HistoryEntry(notification, receivedAt));
        if (entries.size() > LIMIT) entries.removeLast();
    }

    public List<HistoryEntry> snapshot() { return List.copyOf(entries); }
    public HistoryEntry recent() { return entries.peekFirst(); }
    public void clear() { entries.clear(); }
}
