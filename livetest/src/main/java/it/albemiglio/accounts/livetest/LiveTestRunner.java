package it.albemiglio.accounts.livetest;

import it.albemiglio.accounts.core.modules.Module;
import it.albemiglio.accounts.core.modules.YamlModuleFactory;
import it.albemiglio.accounts.core.objects.Pair;
import it.albemiglio.accounts.core.objects.Task;
import it.albemiglio.accounts.api.MigrationStatus;
import it.albemiglio.accounts.core.services.BroadcastMigrationService;
import it.albemiglio.accounts.core.services.InstanceMigrator;
import it.albemiglio.accounts.core.services.MigrationPublisher;
import it.albemiglio.accounts.core.services.MigrationStore;
import it.albemiglio.accounts.core.services.RedisInstanceRegistry;
import it.albemiglio.accounts.core.services.RedisMigrationPublisher;
import it.albemiglio.accounts.core.services.RedisMigrationStore;
import it.albemiglio.accounts.core.services.RedisMigrationSubscriber;
import org.yaml.snakeyaml.Yaml;
import redis.clients.jedis.JedisPool;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * End-to-end live-test of the migration engine against REAL backends. It builds the same modules the
 * plugin builds (via {@link YamlModuleFactory} from the shipped YAML shapes), drives the same broadcast
 * migration the admin command drives ({@link BroadcastMigrationService#migrate}), then asserts the SQL
 * row and the EssentialsX file are now keyed by the NEW UUID and the OLD is gone. Exits non-zero on any
 * mismatch so {@code run.sh} / CI fails loudly.
 *
 * <p>Default mode talks to the dockerized MySQL + Redis (run.sh sets that up). With env
 * {@code LIVETEST_DB=sqlite} it runs the same engine path against a throwaway SQLite file and an
 * in-memory store, so the data-migration logic can be proven offline with no Docker — used to verify
 * the harness itself when Docker is not available.
 */
public final class LiveTestRunner {

    private static final UUID OLD = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private static final UUID NEW = UUID.fromString("c06f8906-4c8a-4911-9c29-ea1dbd1aab82");

    public static void main(String[] args) throws Exception {
        Path base = Paths.get(System.getProperty("livetest.dir", ".")).toAbsolutePath().normalize();
        boolean sqlite = "sqlite".equalsIgnoreCase(System.getenv("LIVETEST_DB"));

        Map<String, Object> lpConfig = loadYaml(base.resolve("modules/luckperms.yml"));
        Map<String, Object> esConfig = loadYaml(base.resolve("modules/essentialsx.yml"));
        // The file module's directory is relative; resolve it against the rig dir so run.sh can run from
        // anywhere. (run.sh also seeds fixtures/userdata fresh each run.)
        esConfig.put("directory", base.resolve((String) esConfig.get("directory")).toString());

        String sqlitePath = null;
        if (sqlite) {
            sqlitePath = base.resolve("target/livetest.db").toString();
            seedSqlite(sqlitePath);
            lpConfig = sqliteModuleConfig(sqlitePath);
        }

        YamlModuleFactory factory = new YamlModuleFactory();
        List<Module> modules = new ArrayList<>();
        modules.add(factory.build(lpConfig));
        modules.add(factory.build(esConfig));

        Task task = new Task();
        task.setMigration(Pair.of(OLD, NEW));
        task.setUsername("Steve");

        runMigration(task, modules, sqlite);

        System.out.println("Migration applied. Verifying...");
        boolean ok = sqlite
                ? assertSqlite(sqlitePath)
                : assertMysql(lpConfig);
        ok &= assertFileMigrated(Paths.get((String) esConfig.get("directory")));

        if (!ok) {
            System.err.println("LIVE TEST FAILED: data was not migrated to the new UUID.");
            System.exit(1);
        }
        System.out.println("LIVE TEST PASSED: SQL row and EssentialsX file are now keyed by " + NEW
                + " and the old UUID " + OLD + " is gone.");
    }

    /** Drives the real broadcast service when Redis is up; otherwise (sqlite mode) the in-memory store. */
    private static void runMigration(Task task, List<Module> modules, boolean sqlite) throws Exception {
        String instanceId = "livetest-1";

        if (sqlite) {
            InMemoryStore store = new InMemoryStore();
            InstanceMigrator migrator = new InstanceMigrator(instanceId, modules, store);
            BroadcastMigrationService svc = new BroadcastMigrationService(
                    instanceId, migrator, store, noopPublisher(), () -> singleton(instanceId));
            svc.migrate(task);
            check(svc.isComplete(InstanceMigrator.migrationId(task)),
                    "completion barrier did not close in sqlite mode");
            return;
        }

        // Real Redis coordination layer: store + publisher + registry, exactly as the plugin wires them.
        String redisHost = System.getenv().getOrDefault("REDIS_HOST", "127.0.0.1");
        int redisPort = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "63790"));
        try (JedisPool pool = new JedisPool(redisHost, redisPort)) {
            RedisMigrationStore store = new RedisMigrationStore(pool);
            RedisInstanceRegistry registry = new RedisInstanceRegistry(pool, instanceId);
            registry.heartbeat(); // so this instance is in the completion barrier
            MigrationPublisher publisher = new RedisMigrationPublisher(pool);
            InstanceMigrator migrator = new InstanceMigrator(instanceId, modules, store);
            BroadcastMigrationService svc =
                    new BroadcastMigrationService(instanceId, migrator, store, publisher, registry);
            RedisMigrationSubscriber subscriber = new RedisMigrationSubscriber(pool, svc);
            subscriber.start();
            // PUBLISH is fire-and-forget: a message sent before the subscriber is listening is dropped by
            // Redis and nothing records it. Real caveat, not just a test detail — see the README.
            check(waitForSubscriber(pool), "the accounts subscriber never attached to the channel");
            publishTheWayNyxDoes(redisHost, redisPort, task);
            String migrationId = InstanceMigrator.migrationId(task);
            check(waitForApplied(store, instanceId, migrationId),
                    "the migration Nyx published was never applied by this instance");
            // The publisher is Nyx, which knows nothing about who must apply: the barrier has to be
            // opened by the receiver, or the network could never report this migration as finished.
            check(waitForCompletion(svc, migrationId),
                    "the completion barrier never closed for the migration Nyx published");
            MigrationStatus status = svc.status(task.getMigration().getLeft(), task.getMigration().getRight());
            check(status.complete() && status.waitingOn().isEmpty(),
                    "status API disagrees with the barrier: " + status);
            check(svc.inFlight().isEmpty(), "a finished migration is still reported as in flight");
            System.out.println("  status API                   " + status + "  OK");
            subscriber.stop();
        }
    }

    /**
     * Publishes the migration the way the Nyx proxy does it: a plain socket writing a RESP PUBLISH, no
     * Jedis, no accounts code. That is the whole integration between the two products — a channel name
     * and one line of text — and until this ran here nothing checked the two sides still agree. Keep the
     * frame identical to Nyx's RedisMigrationSink: "from;to;username;failures", empty username, 0 failures.
     */
    private static void publishTheWayNyxDoes(String host, int port, Task task) throws Exception {
        String channel = "accounts:broadcast";
        String message = task.getMigration().getLeft() + ";" + task.getMigration().getRight() + ";;0";
        System.out.println("  publishing as Nyx would: " + channel + " <- " + message);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2000);
            socket.setSoTimeout(2000);
            OutputStream out = socket.getOutputStream();
            out.write(resp("PUBLISH", channel, message));
            out.flush();
            check(socket.getInputStream().read() != '-', "Redis rejected the Nyx-style PUBLISH");
        }
    }

    /** RESP array of bulk strings — the wire encoding Nyx hand-rolls so it needs no Redis client. */
    private static byte[] resp(String... args) {
        StringBuilder sb = new StringBuilder("*").append(args.length).append("\r\n");
        for (String a : args) {
            sb.append('$').append(a.getBytes(StandardCharsets.UTF_8).length).append("\r\n").append(a).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** The barrier closes on the subscriber thread, a moment after the apply. */
    private static boolean waitForCompletion(BroadcastMigrationService svc, String migrationId)
            throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (svc.isComplete(migrationId)) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    /** Redis drops a PUBLISH with no listener, so the rig waits for the subscription to be registered. */
    private static boolean waitForSubscriber(JedisPool pool) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            try (redis.clients.jedis.Jedis jedis = pool.getResource()) {
                java.util.List<String> numsub = jedis.pubsubNumSub("accounts:broadcast")
                        .entrySet().stream().map(e -> e.getValue().toString())
                        .collect(java.util.stream.Collectors.toList());
                if (!numsub.isEmpty() && !"0".equals(numsub.get(0))) {
                    return true;
                }
            }
            Thread.sleep(100);
        }
        return false;
    }

    /** The publish is fire-and-forget, so the apply lands on the subscriber thread a moment later. */
    private static boolean waitForApplied(MigrationStore store, String instanceId, String migrationId)
            throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (store.appliedInstances(migrationId).contains(instanceId)) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    // --- assertions against the real backends ---

    @SuppressWarnings("unchecked")
    private static boolean assertMysql(Map<String, Object> lpConfig) throws Exception {
        Map<String, Object> db = (Map<String, Object>) lpConfig.get("database");
        String url = "jdbc:mysql://" + db.get("host") + ":" + db.get("port") + "/" + db.get("database");
        Class.forName("com.mysql.cj.jdbc.Driver");
        try (Connection c = DriverManager.getConnection(
                url, (String) db.get("username"), (String) db.get("password"))) {
            return assertUuidSwapped(c, "luckperms_players")
                    && assertUuidSwapped(c, "luckperms_user_permissions");
        }
    }

    private static boolean assertSqlite(String path) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + path)) {
            return assertUuidSwapped(c, "luckperms_players")
                    && assertUuidSwapped(c, "luckperms_user_permissions");
        }
    }

    private static boolean assertUuidSwapped(Connection c, String table) throws Exception {
        int oldRows = count(c, table, OLD);
        int newRows = count(c, table, NEW);
        boolean ok = oldRows == 0 && newRows >= 1;
        System.out.printf("  %-28s old=%d new=%d  %s%n", table, oldRows, newRows, ok ? "OK" : "FAIL");
        return ok;
    }

    private static int count(Connection c, String table, UUID uuid) throws Exception {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM " + table + " WHERE uuid = '" + uuid + "'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static boolean assertFileMigrated(Path userdata) {
        boolean oldGone = !Files.exists(userdata.resolve(OLD + ".yml"));
        boolean newThere = Files.exists(userdata.resolve(NEW + ".yml"));
        boolean ok = oldGone && newThere;
        System.out.printf("  %-28s oldGone=%b newThere=%b  %s%n",
                "essentialsx userdata", oldGone, newThere, ok ? "OK" : "FAIL");
        return ok;
    }

    // --- sqlite fallback helpers (offline proof, no Docker) ---

    private static void seedSqlite(String path) throws Exception {
        Files.createDirectories(Paths.get(path).getParent());
        Files.deleteIfExists(Paths.get(path));
        Class.forName("org.sqlite.JDBC");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE luckperms_players (uuid TEXT, username TEXT, primary_group TEXT)");
            st.execute("CREATE TABLE luckperms_user_permissions (uuid TEXT, permission TEXT, value INT)");
            st.execute("INSERT INTO luckperms_players VALUES ('" + OLD + "', 'Steve', 'vip')");
            st.execute("INSERT INTO luckperms_user_permissions VALUES ('" + OLD + "', 'group.vip', 1)");
            st.execute("INSERT INTO luckperms_user_permissions VALUES ('" + OLD + "', 'essentials.home', 1)");
        }
    }

    private static Map<String, Object> sqliteModuleConfig(String path) {
        Map<String, Object> db = new HashMap<>();
        db.put("type", "sqlite");
        db.put("database", path);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("name", "luckperms");
        cfg.put("platform", "SPIGOT");
        cfg.put("type", "sql");
        cfg.put("enabled", true);
        cfg.put("database", db);
        cfg.put("replacers", List.of(
                replacer("luckperms_players"), replacer("luckperms_user_permissions")));
        return cfg;
    }

    private static Map<String, Object> replacer(String table) {
        Map<String, Object> r = new HashMap<>();
        r.put("table", table);
        r.put("column", "uuid");
        return r;
    }

    private static Map<String, Object> loadYaml(Path file) throws Exception {
        try (InputStream in = Files.newInputStream(file)) {
            return new Yaml().load(in);
        }
    }

    private static void check(boolean cond, String message) {
        if (!cond) {
            System.err.println("LIVE TEST FAILED: " + message);
            System.exit(1);
        }
    }

    private static Set<String> singleton(String s) {
        Set<String> set = new HashSet<>();
        set.add(s);
        return set;
    }

    private static MigrationPublisher noopPublisher() {
        return task -> { /* single-instance sqlite mode: nothing to broadcast to */ };
    }

    /** Minimal in-memory {@link MigrationStore} for the offline sqlite path (Redis stands in normally). */
    private static final class InMemoryStore implements MigrationStore {
        private final Map<String, Task> migrations = new HashMap<>();
        private final Map<String, Set<String>> applied = new HashMap<>();
        private final Map<String, Set<String>> expected = new HashMap<>();
        private final Map<String, Set<String>> failed = new HashMap<>();

        @Override public void record(Task task) {
            migrations.put(InstanceMigrator.migrationId(task), task);
        }
        @Override public Collection<Task> pending(String instanceId) {
            List<Task> out = new ArrayList<>();
            for (Map.Entry<String, Task> e : migrations.entrySet()) {
                if (!applied.getOrDefault(e.getKey(), new HashSet<>()).contains(instanceId)) {
                    out.add(e.getValue());
                }
            }
            return out;
        }
        @Override public boolean hasApplied(String id, String inst) {
            return applied.getOrDefault(id, new HashSet<>()).contains(inst);
        }
        @Override public void markApplied(String id, String inst) {
            applied.computeIfAbsent(id, k -> new HashSet<>()).add(inst);
        }
        @Override public void markFailed(String id, String inst) {
            failed.computeIfAbsent(id, k -> new HashSet<>()).add(inst);
        }
        @Override public void recordExpected(String id, Set<String> insts) {
            expected.computeIfAbsent(id, k -> new HashSet<>()).addAll(insts);
        }
        @Override public boolean recordExpectedIfAbsent(String id, Set<String> insts) {
            if (insts.isEmpty() || expected.containsKey(id)) {
                return false;
            }
            expected.put(id, new HashSet<>(insts));
            return true;
        }
        @Override public Collection<Task> all() {
            return new ArrayList<>(migrations.values());
        }
        @Override public void record(it.albemiglio.accounts.core.objects.Rename rename) { }
        @Override public Collection<it.albemiglio.accounts.core.objects.Rename> pendingRenames(String i) {
            return List.of();
        }
        @Override public Set<String> expectedInstances(String id) {
            return expected.getOrDefault(id, new HashSet<>());
        }
        @Override public Set<String> appliedInstances(String id) {
            return applied.getOrDefault(id, new HashSet<>());
        }
    }

    private LiveTestRunner() {}
}
