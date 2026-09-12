package it.albemiglio.accounts.core.services;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps the timings in Redis, beside the migrations themselves: running totals per module and per
 * instance, plus the last few hundred transfers so an average means something recent rather than an
 * average over the life of the server.
 *
 * <p>Recording must never be the reason a migration fails, so every write here swallows its errors —
 * losing a measurement is not worth losing a transfer.
 */
public final class RedisMigrationTimings implements MigrationTimings {

    private static final String MODULE = "accounts:timing:module:";
    private static final String INSTANCE = "accounts:timing:instance:";
    private static final String RECENT = "accounts:timing:recent";
    /** Enough to average over, small enough that reading them all stays a single cheap call. */
    private static final int KEEP_RECENT = 500;

    private final JedisPool pool;

    public RedisMigrationTimings(JedisPool pool) {
        this.pool = pool;
    }

    @Override
    public void module(String instanceId, String module, long millis, boolean ok) {
        try (Jedis jedis = pool.getResource()) {
            String key = MODULE + module;
            jedis.hincrBy(key, "runs", 1);
            jedis.hincrBy(key, "millis", millis);
            if (!ok) {
                jedis.hincrBy(key, "failures", 1);
            }
            bumpMax(jedis, key, millis);
            jedis.hset(key, "instance", instanceId);
        } catch (RuntimeException e) {
            // a lost measurement is not worth failing a migration over
        }
    }

    @Override
    public void migration(String migrationId, String instanceId, long millis, int modules) {
        try (Jedis jedis = pool.getResource()) {
            String key = INSTANCE + instanceId;
            jedis.hincrBy(key, "runs", 1);
            jedis.hincrBy(key, "millis", millis);
            bumpMax(jedis, key, millis);
            jedis.lpush(RECENT, migrationId + "\t" + instanceId + "\t" + millis + "\t" + modules
                    + "\t" + System.currentTimeMillis());
            jedis.ltrim(RECENT, 0, KEEP_RECENT - 1);
        } catch (RuntimeException e) {
            // as above
        }
    }

    private static void bumpMax(Jedis jedis, String key, long millis) {
        String current = jedis.hget(key, "max");
        if (current == null || millis > Long.parseLong(current)) {
            jedis.hset(key, "max", String.valueOf(millis));
        }
    }

    /** Everything the analytics view shows, read in one pass: per module, per instance, and recent runs. */
    public Snapshot snapshot() {
        try (Jedis jedis = pool.getResource()) {
            Snapshot snapshot = new Snapshot();
            for (String key : jedis.keys(MODULE + "*")) {
                snapshot.modules.put(key.substring(MODULE.length()), jedis.hgetAll(key));
            }
            for (String key : jedis.keys(INSTANCE + "*")) {
                snapshot.instances.put(key.substring(INSTANCE.length()), jedis.hgetAll(key));
            }
            snapshot.recent.addAll(jedis.lrange(RECENT, 0, KEEP_RECENT - 1));
            return snapshot;
        } catch (RuntimeException e) {
            return new Snapshot();
        }
    }

    /** A reading of the counters, not a live view: whatever Redis held at the moment it was asked. */
    public static final class Snapshot {
        public final Map<String, Map<String, String>> modules = new LinkedHashMap<>();
        public final Map<String, Map<String, String>> instances = new LinkedHashMap<>();
        public final List<String> recent = new ArrayList<>();
    }
}
