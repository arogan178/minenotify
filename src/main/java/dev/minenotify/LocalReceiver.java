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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class LocalReceiver implements AutoCloseable {
    public static final int MAX_BODY = 16 * 1024;
    public static final int QUEUE_SIZE = 32;
    private final ArrayBlockingQueue<Notification> notifications = new ArrayBlockingQueue<>(QUEUE_SIZE);
    private final HttpServer server;
    private final ThreadPoolExecutor executor;
    private final byte[] authorization;

    public LocalReceiver(int port, String token) throws IOException {
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
    public Notification poll() { return notifications.poll(); }

    private void handle(HttpExchange request) throws IOException {
        try {
            int status;
            if (!request.getRequestURI().getPath().equals("/notify")) status = 404;
            else if (!request.getRequestMethod().equals("POST")) status = 405;
            else if (!authorized(request)) status = 401;
            else if (!isJson(request)) status = 400;
            else {
                byte[] body = request.getRequestBody().readNBytes(MAX_BODY + 1);
                if (body.length > MAX_BODY) status = 413;
                else {
                    try {
                        String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(body)).toString();
                        status = notifications.offer(Notification.parse(json)) ? 202 : 429;
                    } catch (IOException | RuntimeException e) { status = 400; }
                }
            }
            byte[] response = (switch (status) {
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
        notifications.clear();
    }
}
