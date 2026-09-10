package it.albemiglio.accounts.core.services;

import it.albemiglio.accounts.core.objects.Rename;
import it.albemiglio.accounts.core.objects.Task;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Redis-backed {@link MigrationStore}. Migrations live in a hash (id -> serialized Task) so they
 * survive restarts; the applied/expected/failed sets are per-migration ({@code accounts:applied:<id>}
 * etc.) so the completion barrier can read who has applied vs who must.
 */
public final class RedisMigrationStore implements MigrationStore {

    private static final String MIGRATIONS = "accounts:migrations";
    private static final String RENAMES = "accounts:renames";
    private static final String APPLIED = "accounts:applied:";
    private static final String EXPECTED = "accounts:expected:";
    private static final String FAILED = "accounts:failed:";

    private final JedisPool pool;

    public RedisMigrationStore(JedisPool pool) {
        this.pool = pool;
    }

    @Override
    public void record(Task task) {
        try (Jedis jedis = pool.getResource()) {
            jedis.hset(MIGRATIONS, InstanceMigrator.migrationId(task), task.toString());
        }
    }

    @Override
    public void record(Rename rename) {
        try (Jedis jedis = pool.getResource()) {
            jedis.hset(RENAMES, rename.id(), rename.toString());
        }
    }

    @Override
    public Collection<Rename> pendingRenames(String instanceId) {
        try (Jedis jedis = pool.getResource()) {
            List<Rename> out = new ArrayList<>();
            for (Map.Entry<String, String> entry : jedis.hgetAll(RENAMES).entrySet()) {
                if (!jedis.sismember(APPLIED + entry.getKey(), instanceId)) {
                    out.add(Rename.fromString(entry.getValue()));
                }
            }
            return out;
        }
    }

    @Override
    public Collection<Task> pending(String instanceId) {
        try (Jedis jedis = pool.getResource()) {
            Map<String, String> all = jedis.hgetAll(MIGRATIONS);
            List<Task> out = new ArrayList<>();
            for (Map.Entry<String, String> entry : all.entrySet()) {
                if (!jedis.sismember(APPLIED + entry.getKey(), instanceId)) {
                    out.add(Task.fromString(entry.getValue()));
                }
            }
            return out;
        }
    }

    @Override
    public boolean hasApplied(String migrationId, String instanceId) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.sismember(APPLIED + migrationId, instanceId);
        }
    }

    @Override
    public void markApplied(String migrationId, String instanceId) {
        try (Jedis jedis = pool.getResource()) {
            jedis.sadd(APPLIED + migrationId, instanceId);
            // An instance that failed and then succeeded on a retry is not a failure any more; leaving
            // it listed leaves an operator reading a stale alarm long after the data moved.
            jedis.srem(FAILED + migrationId, instanceId);
        }
    }

    @Override
    public void markFailed(String migrationId, String instanceId) {
        try (Jedis jedis = pool.getResource()) {
            jedis.sadd(FAILED + migrationId, instanceId);
        }
    }

    @Override
    public void recordExpected(String migrationId, Set<String> instances) {
        if (instances.isEmpty()) {
            return;
        }
        try (Jedis jedis = pool.getResource()) {
            jedis.sadd(EXPECTED + migrationId, instances.toArray(new String[0]));
        }
    }

    // EXISTS-then-SADD from Java would race between instances receiving the same broadcast; as a script
    // Redis runs it atomically, so exactly one caller fills the set.
    private static final String CLAIM_BARRIER =
            "if redis.call('exists', KEYS[1]) == 1 then return 0 end "
            + "redis.call('sadd', KEYS[1], unpack(ARGV)) "
            + "return 1";

    @Override
    public boolean recordExpectedIfAbsent(String migrationId, Set<String> instances) {
        if (instances.isEmpty()) {
            return false;
        }
        try (Jedis jedis = pool.getResource()) {
            Object claimed = jedis.eval(CLAIM_BARRIER, Collections.singletonList(EXPECTED + migrationId),
                    new ArrayList<>(instances));
            return Long.valueOf(1L).equals(claimed);
        }
    }

    @Override
    public Collection<Task> all() {
        try (Jedis jedis = pool.getResource()) {
            List<Task> out = new ArrayList<>();
            for (String raw : jedis.hgetAll(MIGRATIONS).values()) {
                out.add(Task.fromString(raw));
            }
            return out;
        }
    }

    @Override
    public Set<String> expectedInstances(String migrationId) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.smembers(EXPECTED + migrationId);
        }
    }

    @Override
    public Set<String> appliedInstances(String migrationId) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.smembers(APPLIED + migrationId);
        }
    }
}
