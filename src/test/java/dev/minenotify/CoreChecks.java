package dev.minenotify;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public final class CoreChecks {
    private static final String TOKEN = "0123456789abcdef".repeat(4);
    private static final String VALID = "{\"source\":\"Codex\",\"title\":\"Task finished\",\"message\":\"Tests passed ✓\"}";
    private static final HttpClient HTTP = HttpClient.newBuilder().build();

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("minenotify-check-");
        try {
            configChecks(directory);
            notificationChecks();
            receiverChecks();
            layoutChecks();
            System.out.println("MineNotify core checks passed. Real HTTP, queue limits, lifecycle, config recovery and layout verified.");
        } finally {
            try (var files = Files.walk(directory)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
            HTTP.close();
        }
    }

    private static void configChecks(Path directory) throws Exception {
        Path path = directory.resolve("config/minenotify.json");
        ConfigFile config = ConfigFile.load(path);
        String token = config.token;
        assert token.matches("[0-9a-f]{64}") : "Configuration creates a 256-bit token";
        config.settings.displayMode = Settings.DisplayMode.BOTH;
        config.settings.position = Settings.Position.TOP_LEFT;
        config.settings.showPreview = true;
        config.settings.scale = 1.25;
        config.port = 49123;
        config.save(path);
        ConfigFile persisted = ConfigFile.load(path);
        assert persisted.token.equals(token) : "Token survives settings changes and restart";
        assert persisted.port == 49123;
        assert persisted.settings.displayMode == Settings.DisplayMode.BOTH;
        assert persisted.settings.position == Settings.Position.TOP_LEFT;
        assert persisted.settings.showPreview;
        assert persisted.settings.scale == 1.25;
        Files.writeString(path, "{\"port\":0,\"token\":\"short\",\"settings\":{\"scale\":999,\"durationSeconds\":0,\"offsetX\":-7,\"position\":\"invalid\"}}");
        ConfigFile corrected = ConfigFile.load(path);
        assert corrected.port == 47653;
        assert corrected.token.length() == 64;
        assert corrected.settings.scale == 1.5;
        assert corrected.settings.durationSeconds == 2;
        assert corrected.settings.offsetX == 0;
        assert corrected.settings.position == Settings.Position.BOTTOM_RIGHT;
        Files.writeString(path, "{broken");
        ConfigFile recovered = ConfigFile.load(path);
        assert recovered.settings.enabled;
        assert recovered.token.matches("[0-9a-f]{64}");
        try (var files = Files.list(path.getParent())) {
            assert files.anyMatch(file -> file.getFileName().toString().startsWith("minenotify.json.invalid-")) : "Broken config retained";
        }
    }

    private static void notificationChecks() throws Exception {
        Notification parsed = Notification.parse(VALID);
        assert parsed.equals(new Notification("Codex", "Task finished", "Tests passed ✓"));
        assert new Notification("Codex", "  Done\nnow  ", "§aHi\u0000\u202e\tthere").message().equals("aHi there");
        assert Notification.parse("{\"source\":\"Codex\",\"title\":\"Done\"}").message().equals("");
        String[] malformed = {"null", "[]", "{}", "{\"source\":3,\"title\":\"Done\"}", "{\"source\":\"Codex\",\"title\":\"\"}",
                VALID + " {}", "/*comment*/" + VALID, "{source:'Codex',title:'Done'}", "{\"source\":\"Codex\",\"title\":\"Done\",}"};
        for (String json : malformed) {
            boolean rejected = false;
            try { Notification.parse(json); }
            catch (Exception e) { rejected = true; }
            assert rejected : "Malformed JSON accepted: " + json;
        }
        boolean rejected = false;
        try { new Notification("Codex", "Done", "x".repeat(2049)); }
        catch (IllegalArgumentException e) { rejected = true; }
        assert rejected : "Long preview rejected";
    }

    private static void receiverChecks() throws Exception {
        int port;
        try (LocalReceiver receiver = new LocalReceiver(0, TOKEN)) {
            port = receiver.port();
            assert send(port, "POST", "/notify", VALID, TOKEN) == 202;
            assert receiver.poll().equals(new Notification("Codex", "Task finished", "Tests passed ✓"));
            assert send(port, "POST", "/notify", VALID, null) == 401;
            assert send(port, "POST", "/notify", VALID, "wrong") == 401;
            assert send(port, "GET", "/notify", "", TOKEN) == 405;
            assert send(port, "POST", "/elsewhere", VALID, TOKEN) == 404;
            assert send(port, "OPTIONS", "/notify", "", TOKEN) == 405;
            assert send(port, "POST", "/notify", "{bad}", TOKEN) == 400;
            var wrongType = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/notify"))
                    .header("Authorization", "Bearer " + TOKEN).header("Content-Type", "text/plain")
                    .POST(HttpRequest.BodyPublishers.ofString(VALID)).build();
            assert HTTP.send(wrongType, HttpResponse.BodyHandlers.ofString()).statusCode() == 400 : "Reject non-JSON content type";
            assert send(port, "POST", "/notify", VALID + " ".repeat(LocalReceiver.MAX_BODY), TOKEN) == 413;
            assert send(port, "POST", "/notify", "{\"source\":\"Codex\",\"title\":\"Done\",\"message\":\"" + "x".repeat(2049) + "\"}", TOKEN) == 400;
            assert receiver.poll() == null : "Invalid traffic never enters queue";
            for (int i = 0; i < 32; i++) assert send(port, "POST", "/notify", VALID, TOKEN) == 202;
            assert send(port, "POST", "/notify", VALID, TOKEN) == 429;
            for (int i = 0; i < 32; i++) assert receiver.poll().title().equals("Task finished");
            assert receiver.poll() == null;
            assert send(port, "POST", "/notify", VALID, TOKEN) == 202 : "Queue recovers after drain";
        }
        try (LocalReceiver restarted = new LocalReceiver(port, TOKEN)) {
            assert send(port, "POST", "/notify", VALID, TOKEN) == 202 : "Port released on shutdown";
            assert restarted.poll().message().equals("Tests passed ✓");
        }
    }

    private static int send(int port, String method, String path, String body, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(java.time.Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        if (token != null) request.header("Authorization", "Bearer " + token);
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assert response.headers().firstValue("Access-Control-Allow-Origin").isEmpty() : "No CORS exposure";
        if (response.statusCode() == 202) assert response.body().equals("{\"status\":\"accepted\"}");
        return response.statusCode();
    }

    private static void layoutChecks() {
        Settings settings = new Settings();
        assert PopupHud.layout(800, 600, 280, 82, settings, 0).equals(new PopupHud.Rect(508, 478, 280, 82));
        settings.position = Settings.Position.TOP_LEFT;
        assert PopupHud.layout(800, 600, 280, 82, settings, 88).equals(new PopupHud.Rect(12, 128, 280, 82));
        settings.position = Settings.Position.TOP_RIGHT;
        settings.offsetX = 160;
        assert PopupHud.layout(300, 240, 280, 82, settings, 0).x() == 0 : "Popup kept on screen at high inset";
        settings.position = Settings.Position.BOTTOM_RIGHT;
        settings.offsetY = 160;
        int visible = 0;
        for (int i = 0; i < 3; i++) {
            PopupHud.Rect rect = PopupHud.layout(300, 240, 280, 82, settings, i * 88);
            if (rect.y() >= 0 && rect.y() + rect.height() <= 240) visible++;
        }
        assert visible == 1 : "Only the newest complete card fits at high inset; older cards cannot overlap it";
    }
}
