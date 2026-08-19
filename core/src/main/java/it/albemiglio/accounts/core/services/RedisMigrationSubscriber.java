package it.albemiglio.accounts.core.services;

import it.albemiglio.accounts.core.objects.Rename;
import it.albemiglio.accounts.core.objects.Task;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Subscribes to the broadcast channel on a dedicated connection and hands every received migration to
 * the {@link BroadcastMigrationService}. The subscribe call blocks its connection for life, which is
 * why it runs on its own thread with its own pooled resource.
 *
 * <p>A dropped/failed Redis connection is not fatal: the thread reconnects with bounded exponential
 * backoff and re-subscribes, running {@link BroadcastMigrationService#recoverPending()} after each
 * reconnect so migrations published during the outage are caught up. Only {@link #stop()} ends the
 * loop.
 */
public class RedisMigrationSubscriber {

    private static final Logger LOG = Logger.getLogger(RedisMigrationSubscriber.class.getName());
    private static final long BACKOFF_INITIAL_MS = 100;
    private static final long BACKOFF_MAX_MS = 5000;

    private final JedisPool pool;
    private final BroadcastMigrationService service;
    private final JedisPubSub pubSub;
    private volatile boolean stopped;
    private Thread thread;

    public RedisMigrationSubscriber(JedisPool pool, BroadcastMigrationService service) {
        this.pool = pool;
        this.service = service;
        this.pubSub = new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                if (Rename.looksLikeOne(message)) {
                    try {
                        service.handle(Rename.fromString(message));
                    } catch (RuntimeException e) {
                        LOG.log(Level.WARNING, "Discarding malformed rename message: " + message, e);
                    }
                    return;
                }
                Task task;
                try {
                    task = Task.fromString(message);
                } catch (RuntimeException e) {
                    // A single malformed payload must not look like a dropped connection (which would
                    // reconnect and lose it silently): drop just this message and keep listening.
                    LOG.log(Level.WARNING, "Discarding malformed broadcast migration message: " + message, e);
                    return;
                }
                service.handle(task);
            }
        };
    }

    public void start() {
        thread = new Thread(this::run, "accounts-migration-subscriber");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        long backoff = BACKOFF_INITIAL_MS;
        while (!stopped) {
            try {
                subscribeOnce();
                // subscribeOnce returned without error: an explicit unsubscribe (stop) or a clean end.
                backoff = BACKOFF_INITIAL_MS;
            } catch (Exception e) {
                if (stopped) {
                    return;
                }
                LOG.log(Level.WARNING, "Redis broadcast subscription dropped; reconnecting in " + backoff + "ms", e);
                if (!sleep(backoff)) {
                    return; // interrupted / stopped during backoff
                }
                backoff = Math.min(backoff * 2, BACKOFF_MAX_MS);
                if (stopped) {
                    return;
                }
                // Catch up on anything broadcast while we were disconnected, then re-subscribe.
                service.recoverPending();
            }
        }
    }

    /** One blocking subscribe on a fresh pooled connection; returns when unsubscribed, throws on connection error. */
    void subscribeOnce() {
        try (Jedis jedis = pool.getResource()) {
            jedis.subscribe(pubSub, RedisMigrationPublisher.CHANNEL);
        }
    }

    /** Sleeps for the given backoff; returns false if interrupted (so the loop should exit). */
    private boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void stop() {
        stopped = true;
        if (pubSub.isSubscribed()) {
            pubSub.unsubscribe();
        }
        if (thread != null) {
            thread.interrupt();
        }
    }
}
