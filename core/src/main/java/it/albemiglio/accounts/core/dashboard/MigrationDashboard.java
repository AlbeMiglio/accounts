package it.albemiglio.accounts.core.dashboard;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import it.albemiglio.accounts.api.MigrationStatus;
import it.albemiglio.accounts.core.services.RedisMigrationTimings;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    private static final SecureRandom RANDOM = new SecureRandom();

    private final HttpServer server;
    /** Temporary link tokens handed out by the command, each with the instant it stops working. */
    private final Map<String, Long> links = new ConcurrentHashMap<>();

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
        return start(bind, port, token, inFlight, RedisMigrationTimings.Snapshot::new);
    }

    /**
     * @param timings supplies the recorded durations; called per request, never cached
     */
    public static MigrationDashboard start(String bind, int port, String token,
                                           Supplier<List<MigrationStatus>> inFlight,
                                           Supplier<RedisMigrationTimings.Snapshot> timings)
            throws IOException {
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("dashboard token is empty: refusing to expose migration data "
                    + "without one. Set dashboard.token in the config, or leave dashboard.enabled false.");
        }
        HttpServer server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        byte[] page = readPage();
        MigrationDashboard dashboard = new MigrationDashboard(server);
        server.createContext("/api/migrations", exchange ->
                dashboard.guarded(exchange, token, () -> respond(exchange, 200, "application/json", json(inFlight.get()))));
        server.createContext("/api/analytics", exchange ->
                dashboard.guarded(exchange, token, () ->
                        respond(exchange, 200, "application/json", analytics(timings.get()))));
        server.createContext("/", exchange ->
                dashboard.guarded(exchange, token, () -> respond(exchange, 200, "text/html; charset=utf-8", page)));
        server.setExecutor(null);
        server.start();
        return dashboard;
    }

    /**
     * A single-use-ish link token, valid for {@code minutes}. The configured token is the operator's
     * standing secret and does not belong in a chat message that lands in a log everyone reads; a
     * command hands out one of these instead, and it expires on its own.
     */
    public String mintLink(int minutes) {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        links.put(token, System.currentTimeMillis() + minutes * 60_000L);
        return token;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** Token from the Authorization header or the query string; anything else gets 401 and no detail. */
    private void guarded(HttpExchange exchange, String token, IoRunnable body) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "text/plain", "GET only".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (!authorised(exchange, token) && !linked(exchange)) {
                respond(exchange, 401, "text/plain", "unauthorised".getBytes(StandardCharsets.UTF_8));
                return;
            }
            body.run();
        } finally {
            exchange.close();
        }
    }

    /** A temporary link token, still inside its window. Expired ones are dropped as they are met. */
    private boolean linked(HttpExchange exchange) {
        String presented = presented(exchange);
        if (presented == null) {
            return false;
        }
        Long until = links.get(presented);
        if (until == null) {
            return false;
        }
        // >= not >: a link whose window is zero was never valid, and the boundary instant is over.
        if (System.currentTimeMillis() >= until) {
            links.remove(presented);
            return false;
        }
        return true;
    }

    private static String presented(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        return header != null && header.startsWith("Bearer ")
                ? header.substring("Bearer ".length())
                : queryParam(exchange.getRequestURI().getRawQuery());
    }

    private static boolean authorised(HttpExchange exchange, String token) {
        String presented = presented(exchange);
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

    /** The recorded durations, shaped for the analytics view: per module, per instance, recent runs. */
    static byte[] analytics(RedisMigrationTimings.Snapshot snapshot) {
        JsonObject root = new JsonObject();
        root.add("modules", counters(snapshot.modules));
        root.add("instances", counters(snapshot.instances));
        JsonArray recent = new JsonArray();
        for (String line : snapshot.recent) {
            String[] parts = line.split("\t");
            if (parts.length < 5) {
                continue;
            }
            JsonObject run = new JsonObject();
            run.addProperty("migration", parts[0]);
            run.addProperty("instance", parts[1]);
            run.addProperty("millis", Long.parseLong(parts[2]));
            run.addProperty("modules", Integer.parseInt(parts[3]));
            run.addProperty("at", Long.parseLong(parts[4]));
            recent.add(run);
        }
        root.add("recent", recent);
        return GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
    }

    private static JsonArray counters(java.util.Map<String, java.util.Map<String, String>> source) {
        JsonArray array = new JsonArray();
        source.forEach((name, fields) -> {
            long runs = number(fields.get("runs"));
            if (runs == 0) {
                return;
            }
            long millis = number(fields.get("millis"));
            JsonObject entry = new JsonObject();
            entry.addProperty("name", name);
            entry.addProperty("runs", runs);
            entry.addProperty("millis", millis);
            entry.addProperty("average", millis / runs);
            entry.addProperty("max", number(fields.get("max")));
            entry.addProperty("failures", number(fields.get("failures")));
            array.add(entry);
        });
        return array;
    }

    private static long number(String value) {
        try {
            return value == null ? 0L : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
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
