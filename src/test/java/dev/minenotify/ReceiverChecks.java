package dev.minenotify;

import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class ReceiverChecks {
    private static final String TOKEN = "receiver-check-secret";
    private static final byte[] VALID = payload("Finished");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception {
        try {
            protocolChecks();
            expiryChecks();
            concurrentChecks();
            System.out.println("Receiver checks passed. HTTP authentication, health, bounds, expiry, concurrent delivery, and restart verified.");
        } finally { HTTP.close(); }
    }

    private static void protocolChecks() throws Exception {
        int port;
        try (LocalReceiver receiver = new LocalReceiver(0, TOKEN)) {
            port = receiver.port();
            assert send(port, "GET", "/status", null, null).statusCode() == 401;
            assert send(port, "GET", "/status", null, "wrong").statusCode() == 401;
            assert send(port, "GET", "/elsewhere", null, TOKEN).statusCode() == 404;
            assert send(port, "POST", "/status", VALID, TOKEN).statusCode() == 405;
            assert send(port, "OPTIONS", "/status", null, TOKEN).statusCode() == 405;
            assert send(port, "GET", "/notify", null, TOKEN).statusCode() == 405;
            assert send(port, "POST", "/notify", "{bad}".getBytes(StandardCharsets.UTF_8), TOKEN).statusCode() == 400;
            assert send(port, "POST", "/notify", "{\"source\":\"Checks\",\"title\":\"\u00c3(\"}".getBytes(StandardCharsets.ISO_8859_1), TOKEN).statusCode() == 400;
            assert send(port, "POST", "/notify", new byte[LocalReceiver.MAX_BODY + 1], TOKEN).statusCode() == 413;
            assert send(port, "POST", "/notify", VALID, "wrong").statusCode() == 401;
            status(port, 0, 0, 0, null);
            for (int i = 0; i < 32; i++) assert send(port, "POST", "/notify", VALID, TOKEN).statusCode() == 202;
            assert send(port, "POST", "/notify", VALID, TOKEN).statusCode() == 429;
            status(port, 32, 32, 0, null);
            status(port, 32, 32, 0, null);
            for (int i = 0; i < 32; i++) assert receiver.poll().equals(new Notification("Checks", "Finished", "Private response"));
            assert receiver.poll() == null;
            status(port, 0, 32, 0, null);
        }
        try (LocalReceiver receiver = new LocalReceiver(port, TOKEN)) {
            status(port, 0, 0, 0, null);
            assert send(port, "POST", "/notify", VALID, TOKEN).statusCode() == 202;
            assert receiver.poll().title().equals("Finished");
        }
    }

    private static void expiryChecks() throws Exception {
        AtomicLong clock = new AtomicLong(TimeUnit.SECONDS.toNanos(7));
        try (LocalReceiver receiver = new LocalReceiver(0, TOKEN, clock::get, TimeUnit.SECONDS.toNanos(300))) {
            int port = receiver.port();
            for (int i = 0; i < 32; i++) assert send(port, "POST", "/notify", payload("Old"), TOKEN).statusCode() == 202;
            clock.addAndGet(TimeUnit.SECONDS.toNanos(299));
            status(port, 32, 32, 0, 299L);
            clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
            assert send(port, "POST", "/notify", payload("Fresh"), TOKEN).statusCode() == 202 : "Expired entries cannot block fresh alerts";
            status(port, 1, 33, 32, 300L);
            assert receiver.poll().title().equals("Fresh");
            assert receiver.poll() == null;
            status(port, 0, 33, 32, 300L);
            assert send(port, "POST", "/notify", VALID, TOKEN).statusCode() == 202;
            clock.addAndGet(TimeUnit.SECONDS.toNanos(300));
            assert receiver.poll() == null : "Poll never delivers expired notifications";
            status(port, 0, 34, 33, 600L);
            assert send(port, "POST", "/notify", VALID, TOKEN).statusCode() == 202;
            clock.addAndGet(TimeUnit.SECONDS.toNanos(300));
            status(port, 0, 35, 34, 900L);
            status(port, 0, 35, 34, 900L);
            assert receiver.poll() == null;
        }
    }

    private static void concurrentChecks() throws Exception {
        AtomicLong clock = new AtomicLong();
        try (LocalReceiver receiver = new LocalReceiver(0, TOKEN, clock::get, 1)) {
            for (int i = 0; i < 32; i++) assert send(receiver.port(), "POST", "/notify", payload("Expired"), TOKEN).statusCode() == 202;
            clock.incrementAndGet();
            Set<String> delivered = ConcurrentHashMap.newKeySet();
            try (var workers = Executors.newFixedThreadPool(8)) {
                var jobs = new ArrayList<java.util.concurrent.Future<?>>();
                for (int i = 0; i < 32; i++) {
                    String title = "Fresh " + i;
                    jobs.add(workers.submit(() -> {
                        try {
                            assert send(receiver.port(), "POST", "/notify", payload(title), TOKEN).statusCode() == 202;
                        } catch (Exception e) { throw new RuntimeException(e); }
                    }));
                }
                jobs.add(workers.submit(() -> {
                    for (int i = 0; i < 100; i++) {
                        Notification next = receiver.poll();
                        if (next != null) assert delivered.add(next.title()) : "No notification delivered twice";
                    }
                }));
                jobs.add(workers.submit(() -> {
                    try {
                        for (int i = 0; i < 10; i++) assert send(receiver.port(), "GET", "/status", null, TOKEN).statusCode() == 200;
                    } catch (Exception e) { throw new RuntimeException(e); }
                }));
                for (var job : jobs) job.get(10, TimeUnit.SECONDS);
            }
            Notification next;
            while ((next = receiver.poll()) != null) assert delivered.add(next.title());
            assert delivered.size() == 32 && !delivered.contains("Expired") : "Exactly the fresh notifications delivered";
            status(receiver.port(), 0, 64, 32, 0L);
        }
    }

    private static void status(int port, int pending, long accepted, long expired, Long uptime) throws Exception {
        var response = send(port, "GET", "/status", null, TOKEN);
        assert response.statusCode() == 200;
        var value = JsonParser.parseString(response.body()).getAsJsonObject();
        assert value.keySet().equals(Set.of("status", "pending", "capacity", "accepted", "expired", "uptimeSeconds"));
        assert value.get("status").getAsString().equals("listening");
        assert value.get("pending").getAsInt() == pending : response.body();
        assert value.get("capacity").getAsInt() == 32;
        assert value.get("accepted").getAsLong() == accepted : response.body();
        assert value.get("expired").getAsLong() == expired : response.body();
        assert value.get("uptimeSeconds").getAsLong() >= 0;
        if (uptime != null) assert value.get("uptimeSeconds").getAsLong() == uptime;
        assert !response.body().contains(TOKEN) && !response.body().contains("Private response");
    }

    private static byte[] payload(String title) {
        return ("{\"source\":\"Checks\",\"title\":\"" + title + "\",\"message\":\"Private response\"}").getBytes(StandardCharsets.UTF_8);
    }

    private static HttpResponse<String> send(int port, String method, String path, byte[] body, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(5))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (body != null) request.header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        var response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assert response.headers().firstValue("Access-Control-Allow-Origin").isEmpty();
        assert response.headers().firstValue("Cache-Control").orElseThrow().equals("no-store");
        assert response.headers().firstValue("Content-Type").orElseThrow().equals("application/json");
        if (response.statusCode() == 202) assert response.body().equals("{\"status\":\"accepted\"}");
        return response;
    }
}
