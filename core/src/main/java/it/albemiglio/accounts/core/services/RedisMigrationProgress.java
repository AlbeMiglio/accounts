package it.albemiglio.accounts.core.services;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.Collections;
import java.util.Map;

/**
 * Live progress in Redis, under a key that expires on its own. Nothing cleans it up: a server that
 * crashes mid-transfer would otherwise leave a bar stuck at forty percent forever, and the transfer's
 * real state is the completion barrier, not this.
 */
public final class RedisMigrationProgress implements MigrationProgress {

    private static final String KEY = "accounts:progress:";
    /** Long enough to outlast any transfer, short enough that a dead one stops being displayed. */
    private static final int TTL_SECONDS = 900;

    private final JedisPool pool;

    public RedisMigrationProgress(JedisPool pool) {
        this.pool = pool;
    }

    @Override
    public void report(String migrationId, String instanceId, int done, int total) {
        try (Jedis jedis = pool.getResource()) {
            jedis.hset(KEY + migrationId, instanceId, done + "/" + total);
            if (done <= 1) {
                jedis.expire(KEY + migrationId, TTL_SECONDS);   // once per instance, not once per module
            }
        } catch (RuntimeException e) {
            // progress is a courtesy; never the reason a transfer fails
        }
    }

    @Override
    public Map<String, String> of(String migrationId) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.hgetAll(KEY + migrationId);
        } catch (RuntimeException e) {
            return Collections.emptyMap();
        }
    }
}
