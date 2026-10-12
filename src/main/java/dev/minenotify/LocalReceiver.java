package dev.minenotify;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

public final class LocalReceiver implements AutoCloseable {
    public static final int MAX_BODY = 16 * 1024;
    public static final int QUEUE_SIZE = 32;
    private record Envelope(Notification notification, long arrivalNanos) {}
    private final ArrayDeque<Envelope> notifications = new ArrayDeque<>(QUEUE_SIZE);
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong expired = new AtomicLong();
    private final LongSupplier clock;
    private final long ttlNanos;
    private final long startedNanos;
    private final HttpServer server;
    private final ThreadPoolExecutor executor;
    private final byte[] authorization;

    public LocalReceiver(int port, String token) throws IOException {
        this(port, token, System::nanoTime, TimeUnit.MINUTES.toNanos(5));
    }

    LocalReceiver(int port, String token, LongSupplier clock, long ttlNanos) throws IOException {
        if (ttlNanos <= 0) throw new IllegalArgumentException("Notification lifetime must be positive");
        this.clock = clock;
        this.ttlNanos = ttlNanos;
        startedNanos = clock.getAsLong();
        authorization = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 16);
        executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), task -> {
            Thread thread = new Thread(task, "MineNotify receiver");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.CallerRunsPolicy());
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    public int port() { return server.getAddress().getPort(); }
    public Notification poll() {
        synchronized (notifications) {
            discardExpired(clock.getAsLong());
            Envelope next = notifications.poll();
            return next == null ? null : next.notification();
        }
    }

    private boolean enqueue(Notification notification) {
        synchronized (notifications) {
            long now = clock.getAsLong();
            discardExpired(now);
            if (notifications.size() == QUEUE_SIZE) return false;
            notifications.add(new Envelope(notification, now));
            accepted.incrementAndGet();
            return true;
        }
    }

    private void discardExpired(long now) {
        while (!notifications.isEmpty() && now - notifications.peek().arrivalNanos() >= ttlNanos) {
            notifications.remove();
            expired.incrementAndGet();
        }
    }

    private String snapshot() {
        synchronized (notifications) {
            long now = clock.getAsLong();
            discardExpired(now);
            return "{\"status\":\"listening\",\"pending\":" + notifications.size()
                    + ",\"capacity\":" + QUEUE_SIZE + ",\"accepted\":" + accepted.get()
                    + ",\"expired\":" + expired.get() + ",\"uptimeSeconds\":"
                    + TimeUnit.NANOSECONDS.toSeconds(Math.max(0, now - startedNanos)) + "}";
        }
    }

    private void handle(HttpExchange request) throws IOException {
        try {
            int status;
            String health = null;
            String path = request.getRequestURI().getPath();
            if (!path.equals("/notify") && !path.equals("/status")) status = 404;
            else if (!request.getRequestMethod().equals(path.equals("/status") ? "GET" : "POST")) status = 405;
            else if (!authorized(request)) status = 401;
            else if (path.equals("/status")) {
                status = 200;
                health = snapshot();
            }
            else if (!isJson(request)) status = 400;
            else {
                byte[] body = request.getRequestBody().readNBytes(MAX_BODY + 1);
                if (body.length > MAX_BODY) status = 413;
                else {
                    try {
                        String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(body)).toString();
                        status = enqueue(Notification.parse(json)) ? 202 : 429;
                    } catch (IOException | RuntimeException e) { status = 400; }
                }
            }
            byte[] response = (health != null ? health : switch (status) {
                case 202 -> "{\"status\":\"accepted\"}";
                case 429 -> "{\"error\":\"queue full\"}";
                default -> "{\"error\":\"request rejected\"}";
            }).getBytes(StandardCharsets.UTF_8);
            request.getResponseHeaders().set("Content-Type", "application/json");
            request.getResponseHeaders().set("Cache-Control", "no-store");
            request.sendResponseHeaders(status, response.length);
            request.getResponseBody().write(response);
        } finally { request.close(); }
    }

    private boolean authorized(HttpExchange request) {
        String supplied = request.getRequestHeaders().getFirst("Authorization");
        return supplied != null && MessageDigest.isEqual(authorization, supplied.getBytes(StandardCharsets.UTF_8));
    }

    private boolean isJson(HttpExchange request) {
        String type = request.getRequestHeaders().getFirst("Content-Type");
        return type != null && type.split(";", 2)[0].strip().equalsIgnoreCase("application/json");
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
        synchronized (notifications) { notifications.clear(); }
    }
}
