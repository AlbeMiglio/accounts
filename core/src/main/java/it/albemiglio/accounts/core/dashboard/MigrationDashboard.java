package it.albemiglio.accounts.core.dashboard;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import it.albemiglio.accounts.api.MigrationStatus;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.function.Supplier;

/**
 * A read-only admin view of the migrations currently in flight: which player, how many instances have
 * applied it, and which ones are still holding it open. Answers "what is happening right now" without
 * anyone reading Redis by hand.
 *
 * <p>It is an HTTP surface on a server that had none, so it is deliberately narrow: refuses to start
 * without a token, compares that token in constant time, serves only GET, and binds wherever the
 * operator says — the shipped default being loopback, so reaching it means an SSH tunnel rather than an
 * open port. Nothing here writes: the worst a leaked token buys is a list of UUIDs in transit.
 */
public final class MigrationDashboard implements AutoCloseable {

    private static final Gson GSON = new Gson();

    private final HttpServer server;

    private MigrationDashboard(HttpServer server) {
        this.server = server;
    }

    /**
     * @param bind     address to listen on; use {@code 127.0.0.1} unless the port is firewalled
     * @param port     0 picks a free port (used by the tests; read it back with {@link #port()})
     * @param token    shared secret required on every request — empty is refused, not defaulted
     * @param inFlight supplies the current migrations; called per request, never cached
     */
    public static MigrationDashboard start(String bind, int port, String token,
                                           Supplier<List<MigrationStatus>> inFlight) throws IOException {
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("dashboard token is empty: refusing to expose migration data "
                    + "without one. Set dashboard.token in the config, or leave dashboard.enabled false.");
        }
        HttpServer server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        byte[] page = readPage();
        server.createContext("/api/migrations", exchange ->
                guarded(exchange, token, () -> respond(exchange, 200, "application/json", json(inFlight.get()))));
        server.createContext("/", exchange ->
                guarded(exchange, token, () -> respond(exchange, 200, "text/html; charset=utf-8", page)));
        server.setExecutor(null);
        server.start();
        return new MigrationDashboard(server);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** Token from the Authorization header or the query string; anything else gets 401 and no detail. */
    private static void guarded(HttpExchange exchange, String token, IoRunnable body) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "text/plain", "GET only".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (!authorised(exchange, token)) {
                respond(exchange, 401, "text/plain", "unauthorised".getBytes(StandardCharsets.UTF_8));
                return;
            }
            body.run();
        } finally {
            exchange.close();
        }
    }

    private static boolean authorised(HttpExchange exchange, String token) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        String presented = header != null && header.startsWith("Bearer ")
                ? header.substring("Bearer ".length())
                : queryParam(exchange.getRequestURI().getRawQuery());
        if (presented == null) {
            return false;
        }
        // Constant-time: a byte-by-byte comparison leaks the token one character at a time to anyone
        // who can measure the response.
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }

    private static String queryParam(String rawQuery) {
        if (rawQuery == null) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.startsWith("token=")) {
                return java.net.URLDecoder.decode(pair.substring("token=".length()), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    static byte[] json(List<MigrationStatus> inFlight) {
        JsonArray array = new JsonArray();
        for (MigrationStatus status : inFlight) {
            JsonObject entry = new JsonObject();
            entry.addProperty("from", String.valueOf(status.from()));
            entry.addProperty("to", String.valueOf(status.to()));
            entry.addProperty("username", status.username());
            entry.add("applied", GSON.toJsonTree(status.applied()));
            entry.add("waitingOn", GSON.toJsonTree(status.waitingOn()));
            entry.addProperty("appliedCount", status.applied().size());
            entry.addProperty("expectedCount", status.expected().size());
            array.add(entry);
        }
        JsonObject root = new JsonObject();
        root.add("inFlight", array);
        return GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] readPage() throws IOException {
        try (InputStream in = MigrationDashboard.class.getResourceAsStream("/dashboard.html")) {
            if (in == null) {
                throw new IOException("dashboard.html missing from the jar");
            }
            return in.readAllBytes();
        }
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private interface IoRunnable {
        void run() throws IOException;
    }
}
