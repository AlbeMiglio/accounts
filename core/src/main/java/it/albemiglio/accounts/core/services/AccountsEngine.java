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
    private final RedisMigrationSubscriber subscriber;
    private final ScheduledExecutorService heartbeat;
    private final JedisPool pool;

    private AccountsEngine(BroadcastMigrationService service, RedisMigrationSubscriber subscriber,
                           ScheduledExecutorService heartbeat, JedisPool pool) {
        this.service = service;
        this.subscriber = subscriber;
        this.heartbeat = heartbeat;
        this.pool = pool;
    }

    public static AccountsEngine start(String host, int port, String password,
                                       String instanceId, Collection<Module> modules) {
        JedisPool pool = (password == null || password.isEmpty())
                ? new JedisPool(host, port)
                : new JedisPool(new JedisPoolConfig(), host, port, 2000, password);

        RedisMigrationStore store = new RedisMigrationStore(pool);
        RedisInstanceRegistry registry = new RedisInstanceRegistry(pool, instanceId);
        registry.heartbeat();

        InstanceMigrator migrator = new InstanceMigrator(instanceId, modules, store);
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

        return new AccountsEngine(service, subscriber, heartbeat, pool);
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
