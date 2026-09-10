package it.albemiglio.accounts.core.services;

import it.albemiglio.accounts.core.objects.Task;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the subscriber's auto-reconnect loop. No live Redis: the blocking subscribe call is
 * stubbed via the {@code subscribeOnce()} seam so we can drive a connection drop deterministically.
 */
class RedisMigrationSubscriberTest {

    /** Counts recoverPending() calls: it is the only thing recoverPending touches on the store. */
    static class CountingStore implements MigrationStore {
        final AtomicInteger recoveries = new AtomicInteger();

        @Override
        public Collection<Task> pending(String instanceId) {
            recoveries.incrementAndGet();
            return List.of();
        }

        @Override public void record(Task task) { }
        @Override public void recordExpected(String migrationId, Set<String> instances) { }
        @Override public Set<String> expectedInstances(String migrationId) { return Set.of(); }
        @Override public Set<String> appliedInstances(String migrationId) { return Set.of(); }
        @Override public boolean hasApplied(String migrationId, String instanceId) { return false; }
        @Override public void markApplied(String migrationId, String instanceId) { }
        @Override public void markFailed(String migrationId, String instanceId) { }
        @Override public boolean recordExpectedIfAbsent(String id, Set<String> instances) { return false; }
        @Override public Collection<Task> all() { return List.of(); }
        @Override public void record(it.albemiglio.accounts.core.objects.Rename rename) { }
        @Override public Collection<it.albemiglio.accounts.core.objects.Rename> pendingRenames(String i) { return List.of(); }
    }

    private static BroadcastMigrationService service(CountingStore store) {
        return new BroadcastMigrationService("inst-1",
                new InstanceMigrator("inst-1", List.of(), store), store, new RecordingPublisher(), new FakeRegistry());
    }

    static class RecordingPublisher implements MigrationPublisher {
        @Override public void publish(Task task) { }
    }

    static class FakeRegistry implements InstanceRegistry {
        @Override public Set<String> activeInstances() { return Set.of(); }
    }

    @Test
    void reconnectsAndRecoversAfterAConnectionDrop() throws InterruptedException {
        CountingStore store = new CountingStore();
        AtomicInteger subscribeCalls = new AtomicInteger();
        CountDownLatch resubscribed = new CountDownLatch(2); // first attempt + re-subscribe after the drop

        // pool is never touched because subscribeOnce is overridden.
        RedisMigrationSubscriber subscriber = new RedisMigrationSubscriber(null, service(store)) {
            @Override
            void subscribeOnce() {
                int call = subscribeCalls.incrementAndGet();
                resubscribed.countDown();
                if (call == 1) {
                    throw new RuntimeException("connection reset"); // first connection drops
                }
                block(); // second connection stays up until stop()
            }
        };

        subscriber.start();
        assertTrue(resubscribed.await(2, TimeUnit.SECONDS), "subscriber did not re-subscribe after the drop");
        subscriber.stop();

        assertTrue(subscribeCalls.get() >= 2, "expected a re-subscribe after the drop, got " + subscribeCalls.get());
        assertEquals(1, store.recoveries.get(), "recoverPending should run exactly once per reconnect");
    }

    @Test
    void explicitStopDoesNotReconnect() throws InterruptedException {
        CountingStore store = new CountingStore();
        AtomicInteger subscribeCalls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);

        RedisMigrationSubscriber subscriber = new RedisMigrationSubscriber(null, service(store)) {
            @Override
            void subscribeOnce() {
                subscribeCalls.incrementAndGet();
                entered.countDown();
                block(); // stays "connected" until stop() interrupts us
            }
        };

        subscriber.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        subscriber.stop();
        Thread.sleep(300); // give any (buggy) reconnect a chance to fire

        assertEquals(1, subscribeCalls.get(), "stop() must not trigger a reconnect");
        assertEquals(0, store.recoveries.get(), "no recoverPending after an explicit stop");
    }

    /** Blocks until the thread is interrupted, mimicking Jedis#subscribe holding the connection open. */
    private static void block() {
        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted", e);
        }
    }
}
