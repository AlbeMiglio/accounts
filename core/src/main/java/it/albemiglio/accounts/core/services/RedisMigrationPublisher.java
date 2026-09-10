package it.albemiglio.accounts.core.services;

import it.albemiglio.accounts.core.objects.Task;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/** Broadcasts a migration to every accounts instance over a Redis pub/sub channel. */
public final class RedisMigrationPublisher implements MigrationPublisher {

    static final String CHANNEL = "accounts:broadcast";

    private final JedisPool pool;

    public RedisMigrationPublisher(JedisPool pool) {
        this.pool = pool;
    }

    @Override
    public void publish(Task task) {
        try (Jedis jedis = pool.getResource()) {
            jedis.publish(CHANNEL, task.toString());
        }
    }

    @Override
    public void publish(it.albemiglio.accounts.core.objects.Rename rename) {
        try (redis.clients.jedis.Jedis jedis = pool.getResource()) {
            jedis.publish(CHANNEL, rename.toString());
        }
    }
}
