package it.albemiglio.accounts.core.services;

import it.albemiglio.accounts.api.MigrationStatus;
import it.albemiglio.accounts.core.modules.Module;
import it.albemiglio.accounts.core.objects.Pair;
import it.albemiglio.accounts.core.objects.Task;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Wires the broadcast stack a platform plugin needs and is the one handle it keeps: build it on
 * enable, call {@link #migrate} from the command or Nyx, ask {@link #isComplete} for the unlock gate,
 * {@link #close} on disable. Starting it registers this instance, subscribes for broadcasts, recovers
 * anything it missed while down, and heartbeats so the completion barrier knows it is alive.
 */
public final class AccountsEngine implements AutoCloseable {

    private final BroadcastMigrationService service;
    private final RedisMigrationStore store;
    private final RedisInstanceRegistry registry;
    private final RedisMigrationTimings timings;
    private final RedisMigrationSubscriber subscriber;
    private final ScheduledExecutorService heartbeat;
    private final JedisPool pool;

    private AccountsEngine(BroadcastMigrationService service, RedisMigrationStore store,
                           RedisInstanceRegistry registry,
                           RedisMigrationTimings timings, RedisMigrationSubscriber subscriber,
                           ScheduledExecutorService heartbeat, JedisPool pool) {
        this.service = service;
        this.store = store;
        this.registry = registry;
        this.timings = timings;
        this.subscriber = subscriber;
        this.heartbeat = heartbeat;
        this.pool = pool;
    }

    public static AccountsEngine start(String host, int port, String password,
                                       String instanceId, Collection<Module> modules) {
        JedisPool pool = newPool(host, port, password);

        RedisMigrationStore store = new RedisMigrationStore(pool);
        RedisInstanceRegistry registry = new RedisInstanceRegistry(pool, instanceId);
        registry.heartbeat();

        RedisMigrationTimings timings = new RedisMigrationTimings(pool);
        InstanceMigrator migrator = new InstanceMigrator(instanceId, modules, store, timings);
        RedisMigrationPublisher publisher = new RedisMigrationPublisher(pool);
        BroadcastMigrationService service = new BroadcastMigrationService(instanceId, migrator, store, publisher, registry);

        RedisMigrationSubscriber subscriber = new RedisMigrationSubscriber(pool, service);
        subscriber.start();
        service.recoverPending();

        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "accounts-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        heartbeat.scheduleAtFixedRate(registry::heartbeat, 10, 10, TimeUnit.SECONDS);

        return new AccountsEngine(service, store, registry, timings, subscriber, heartbeat, pool);
    }

    /**
     * Applies what this instance still owes, then lets go of Redis — no subscriber, no heartbeat.
     * A Bukkit plugin calls this from {@code onLoad}: an embedded database (H2, SQLite) is held open by
     * the plugin that owns it for as long as the server runs, so a migration that arrived while the
     * server was up could never touch it. onLoad is the one moment nothing has opened anything yet, and
     * it runs before every plugin's onEnable — including the ones that would otherwise win the lock.
     * The full {@link #start} that follows recovers the same tasks again; apply is idempotent.
     */
    public static void catchUp(String host, int port, String password, String instanceId,
                               Collection<Module> modules) {
        try (JedisPool pool = newPool(host, port, password)) {
            RedisMigrationStore store = new RedisMigrationStore(pool);
            new BroadcastMigrationService(instanceId, new InstanceMigrator(instanceId, modules, store),
                    store, new RedisMigrationPublisher(pool), new RedisInstanceRegistry(pool, instanceId))
                    .recoverPending();
        }
    }

    private static JedisPool newPool(String host, int port, String password) {
        return (password == null || password.isEmpty())
                ? new JedisPool(host, port)
                : new JedisPool(new JedisPoolConfig(), host, port, 2000, password);
    }

    public void migrate(Task task) {
        service.migrate(task);
    }

    /** Convenience entry for callers (the admin command, Nyx) that have the raw ids. */
    public void migrate(UUID from, UUID to, String username) {
        Task task = new Task();
        task.setMigration(Pair.of(from, to));
        task.setUsername(username == null ? "" : username);
        task.setCurrFailures(0);
        service.migrate(task);
    }

    /** Applies a name change across the network, the same way a uuid migration travels. */
    public void rename(UUID uuid, String oldName, String newName) {
        service.rename(new it.albemiglio.accounts.core.objects.Rename(uuid, oldName, newName));
    }

    /** Where a migration has got to: who still owes it, who has applied it. */
    /** Which servers have heartbeated recently — the ones a transfer will actually reach. */
    public java.util.Set<String> activeInstances() {
        return registry.activeInstances();
    }

    /** Every transfer a name or an identity has been part of, newest first. */
    public List<RedisMigrationStore.Transfer> history(String nameOrUuid) {
        return store.history(nameOrUuid);
    }

    /** How long transfers have been taking, and which modules and instances are the slow ones. */
    public RedisMigrationTimings.Snapshot timings() {
        return timings.snapshot();
    }

    public MigrationStatus status(UUID from, UUID to) {
        return service.status(from, to);
    }

    /** Every migration started and not finished across the network. */
    public List<MigrationStatus> inFlight() {
        return service.inFlight();
    }

    /** Whether the migration from -> to is fully applied across every expected instance. */
    public boolean isComplete(UUID from, UUID to) {
        return service.isComplete(InstanceMigrator.migrationId(from, to));
    }

    /** Whether the migration from -> to is started but not yet finished (Nyx's login lock). */
    public boolean isInProgress(UUID from, UUID to) {
        return service.isInProgress(InstanceMigrator.migrationId(from, to));
    }

    @Override
    public void close() {
        heartbeat.shutdownNow();
        subscriber.stop();
        pool.close();
    }
}
