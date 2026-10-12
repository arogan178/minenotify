package dev.minenotify;

import java.time.Instant;
import java.util.List;

public final class HistoryChecks {
    public static void main(String[] args) {
        NotificationHistory history = new NotificationHistory();
        assert history.snapshot().isEmpty();
        assert history.recent() == null;
        Instant firstTime = Instant.parse("2026-10-12T10:00:00Z");
        Notification first = new Notification("Claude", "First result", "Private response");
        history.add(first, firstTime);
        assert history.recent().notification().equals(first);
        assert history.recent().receivedAt().equals(firstTime);
        List<NotificationHistory.HistoryEntry> oldSnapshot = history.snapshot();
        for (int i = 1; i <= 50; i++) {
            history.add(new Notification("Codex", "Result " + i, ""), firstTime.plusSeconds(i));
        }
        List<NotificationHistory.HistoryEntry> full = history.snapshot();
        assert full.size() == 50 : "History retains only the last 50 notifications";
        assert full.getFirst().notification().title().equals("Result 50");
        assert full.getLast().notification().title().equals("Result 1");
        assert full.getFirst().receivedAt().equals(Instant.parse("2026-10-12T10:00:50Z"));
        assert oldSnapshot.size() == 1 && oldSnapshot.getFirst().notification().equals(first) : "Snapshots do not change after additions";
        boolean immutable = false;
        try { full.clear(); } catch (UnsupportedOperationException expected) { immutable = true; }
        assert immutable : "Callers cannot mutate the history through a snapshot";
        history.clear();
        assert history.snapshot().isEmpty() && history.recent() == null : "Clearing removes every stored notification";
        assert full.size() == 50 : "Clearing does not mutate an existing snapshot";
        history.clear();
        history.add(first, firstTime);
        assert history.snapshot().size() == 1 : "History can receive notifications after repeated clearing";
        System.out.println("MineNotify history checks passed. Capacity, newest-first order, timestamps, immutable snapshots, and clearing verified.");
    }
}
