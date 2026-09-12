package it.albemiglio.accounts.core.services;

import it.albemiglio.accounts.core.modules.Diagnosis;
import it.albemiglio.accounts.core.modules.Module;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Asks every server where a player's data actually is, and collects what they answer.
 *
 * <p>The broadcast channel only ever went one way — the proxy tells, the backends do — which is right
 * for a migration and useless for a question. Nobody could ask Kingdoms "is this player's data where
 * your modules think it is?", so the read-only pre-flight was a console command whose output scrolled
 * past on one machine. This carries it: the question goes out on its own channel, each server writes
 * its answer under the request's key, and the asker reads them back until everyone has spoken or the
 * wait runs out. A server that says nothing is reported as silent rather than as healthy.
 */
public final class DiagnoseBus {

    static final String ASK_CHANNEL = "accounts:ask";
    private static final String ANSWER = "accounts:answer:";
    /** Long enough for a slow database, short enough that a browser is not left hanging. */
    private static final long WAIT_MS = 6000;
    private static final long POLL_MS = 120;
    private static final int ANSWER_TTL_SECONDS = 120;

    private static final Logger LOG = Logger.getLogger(DiagnoseBus.class.getName());

    private final JedisPool pool;

    public DiagnoseBus(JedisPool pool) {
        this.pool = pool;
    }

    /**
     * @param expected the servers worth waiting for; the wait ends early once they have all answered
     * @return instance id to its findings, one line per finding, plus the silent ones with no entry
     */
    public Map<String, List<String>> ask(UUID probe, Set<String> expected) {
        String requestId = UUID.randomUUID().toString();
        try (Jedis jedis = pool.getResource()) {
            jedis.publish(ASK_CHANNEL, "diagnose " + requestId + " " + probe);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "could not ask the servers about " + probe, e);
            return new LinkedHashMap<>();
        }
        long deadline = System.currentTimeMillis() + WAIT_MS;
        Map<String, String> answers = new LinkedHashMap<>();
        while (System.currentTimeMillis() < deadline) {
            try (Jedis jedis = pool.getResource()) {
                answers = jedis.hgetAll(ANSWER + requestId);
            } catch (RuntimeException e) {
                break;
            }
            if (!expected.isEmpty() && answers.keySet().containsAll(expected)) {
                break;
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        try (Jedis jedis = pool.getResource()) {
            jedis.del(ANSWER + requestId);
        } catch (RuntimeException e) {
            // the key expires on its own; failing to tidy it is not worth reporting
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        answers.forEach((instance, blob) -> out.put(instance,
                blob.isEmpty() ? new ArrayList<>() : java.util.Arrays.asList(blob.split("\n"))));
        return out;
    }

    /** The answering half: runs the read-only probe over this server's modules and writes the result. */
    public void answer(String requestId, String instanceId, UUID probe, Collection<Module> modules) {
        List<String> lines = new ArrayList<>();
        for (Module module : modules) {
            try {
                for (Diagnosis diagnosis : module.diagnose(probe)) {
                    lines.add(diagnosis.getModule() + "\t" + diagnosis.getLocation() + "\t"
                            + diagnosis.getStatus() + "\t" + diagnosis.getDetail());
                }
            } catch (RuntimeException e) {
                lines.add(module.getName() + "\tmodule\tERROR\t" + e);
            }
        }
        try (Jedis jedis = pool.getResource()) {
            jedis.hset(ANSWER + requestId, instanceId, String.join("\n", lines));
            jedis.expire(ANSWER + requestId, ANSWER_TTL_SECONDS);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "could not answer the diagnose request " + requestId, e);
        }
    }
}
