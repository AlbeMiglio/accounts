package it.albemiglio.accounts.core.services;

import it.albemiglio.accounts.api.MigrationStatus;
import it.albemiglio.accounts.core.modules.Module;
import it.albemiglio.accounts.core.objects.Pair;
import it.albemiglio.accounts.core.objects.Task;
import it.albemiglio.accounts.core.objects.enums.Platform;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BroadcastMigrationServiceTest {

    private static final UUID OLD = new UUID(0L, 1L);
    private static final UUID NEW = new UUID(0L, 2L);

    static class RecordingModule extends Module {
        final List<Pair<UUID, UUID>> executed = new ArrayList<>();

        RecordingModule() {
            super("rec", Platform.SPIGOT, null);
            enable();
        }

        @Override
        public void execute(Pair<UUID, UUID> migration) {
            executed.add(migration);
        }
    }

    static class FakeStore implements MigrationStore {
        final Map<String, Task> recorded = new HashMap<>();
        final Map<String, Set<String>> applied = new HashMap<>();
        final Map<String, Set<String>> expected = new HashMap<>();

        @Override
        public void record(Task task) {
            recorded.put(InstanceMigrator.migrationId(task), task);
        }

        @Override
        public Collection<Task> pending(String instanceId) {
            List<Task> out = new ArrayList<>();
            for (Map.Entry<String, Task> e : recorded.entrySet()) {
                if (!applied.getOrDefault(e.getKey(), Set.of()).contains(instanceId)) {
                    out.add(e.getValue());
                }
            }
            return out;
        }

        @Override
        public boolean hasApplied(String migrationId, String instanceId) {
            return applied.getOrDefault(migrationId, Set.of()).contains(instanceId);
        }

        @Override
        public void markApplied(String migrationId, String instanceId) {
            applied.computeIfAbsent(migrationId, k -> new HashSet<>()).add(instanceId);
        }

        @Override
        public void markFailed(String migrationId, String instanceId) {
        }

        @Override
        public void recordExpected(String migrationId, Set<String> instances) {
            expected.put(migrationId, new HashSet<>(instances));
        }

        @Override
        public boolean recordExpectedIfAbsent(String migrationId, Set<String> instances) {
            if (instances.isEmpty() || expected.containsKey(migrationId)) {
                return false;
            }
            expected.put(migrationId, new HashSet<>(instances));
            return true;
        }

        final Map<String, it.albemiglio.accounts.core.objects.Rename> renames = new HashMap<>();

        @Override
        public void record(it.albemiglio.accounts.core.objects.Rename rename) {
            renames.put(rename.id(), rename);
        }

        @Override
        public Collection<it.albemiglio.accounts.core.objects.Rename> pendingRenames(String instanceId) {
            List<it.albemiglio.accounts.core.objects.Rename> out = new ArrayList<>();
            for (Map.Entry<String, it.albemiglio.accounts.core.objects.Rename> e : renames.entrySet()) {
                if (!applied.getOrDefault(e.getKey(), Set.of()).contains(instanceId)) {
                    out.add(e.getValue());
                }
            }
            return out;
        }

        @Override
        public Collection<Task> all() {
            return new ArrayList<>(recorded.values());
        }

        @Override
        public Set<String> expectedInstances(String migrationId) {
            return expected.getOrDefault(migrationId, Set.of());
        }

        @Override
        public Set<String> appliedInstances(String migrationId) {
            return applied.getOrDefault(migrationId, Set.of());
        }
    }

    static class FakeRegistry implements InstanceRegistry {
        final Set<String> active = new HashSet<>();

        @Override
        public Set<String> activeInstances() {
            return active;
        }
    }

    static class RecordingPublisher implements MigrationPublisher {
        final List<Task> published = new ArrayList<>();

        @Override
        public void publish(Task task) {
            published.add(task);
        }
    }

    private static Task task(UUID oldId, UUID newId) {
        Task t = new Task();
        t.setMigration(Pair.of(oldId, newId));
        t.setUsername("Notch");
        return t;
    }

    private static BroadcastMigrationService service(RecordingModule module, FakeStore store, RecordingPublisher pub) {
        FakeRegistry registry = new FakeRegistry();
        registry.active.add("inst-1");
        InstanceMigrator migrator = new InstanceMigrator("inst-1", List.of(module), store);
        return new BroadcastMigrationService("inst-1", migrator, store, pub, registry);
    }

    @Test
    void migrateRecordsAppliesLocallyAndPublishes() {
        RecordingModule module = new RecordingModule();
        FakeStore store = new FakeStore();
        RecordingPublisher pub = new RecordingPublisher();

        service(module, store, pub).migrate(task(OLD, NEW));

        assertTrue(store.recorded.containsKey(InstanceMigrator.migrationId(task(OLD, NEW))));
        assertEquals(List.of(Pair.of(OLD, NEW)), module.executed);
        assertEquals(1, pub.published.size());
    }

    @Test
    void handleAppliesAReceivedMigration() {
        RecordingModule module = new RecordingModule();
        FakeStore store = new FakeStore();
        RecordingPublisher pub = new RecordingPublisher();

        service(module, store, pub).handle(task(OLD, NEW));

        assertEquals(List.of(Pair.of(OLD, NEW)), module.executed);
    }

    @Test
    void handleAlsoRecordsSoOfflineInstancesCanCatchUp() {
        RecordingModule module = new RecordingModule();
        FakeStore store = new FakeStore();
        RecordingPublisher pub = new RecordingPublisher();

        service(module, store, pub).handle(task(OLD, NEW));

        assertTrue(store.recorded.containsKey(InstanceMigrator.migrationId(task(OLD, NEW))));
    }

    @Test
    void recoverPendingAppliesEverythingThisInstanceStillOwes() {
        RecordingModule module = new RecordingModule();
        FakeStore store = new FakeStore();
        RecordingPublisher pub = new RecordingPublisher();
        store.record(task(OLD, NEW));

        service(module, store, pub).recoverPending();

        assertEquals(List.of(Pair.of(OLD, NEW)), module.executed);
    }

    @Test
    void migrateSnapshotsTheExpectedInstancesFromTheRegistry() {
        FakeStore store = new FakeStore();
        FakeRegistry registry = new FakeRegistry();
        registry.active.addAll(Set.of("proxy-1", "backend-1", "backend-2"));
        BroadcastMigrationService service = new BroadcastMigrationService("proxy-1",
                new InstanceMigrator("proxy-1", List.of(new RecordingModule()), store), store, new RecordingPublisher(), registry);

        service.migrate(task(OLD, NEW));

        assertEquals(Set.of("proxy-1", "backend-1", "backend-2"),
                store.expectedInstances(InstanceMigrator.migrationId(task(OLD, NEW))));
    }

    @Test
    void isCompleteOnlyOnceEveryExpectedInstanceHasApplied() {
        FakeStore store = new FakeStore();
        String id = InstanceMigrator.migrationId(task(OLD, NEW));
        store.recordExpected(id, Set.of("a", "b"));
        BroadcastMigrationService service = new BroadcastMigrationService("a",
                new InstanceMigrator("a", List.of(), store), store, new RecordingPublisher(), new FakeRegistry());

        store.markApplied(id, "a");
        assertFalse(service.isComplete(id));

        store.markApplied(id, "b");
        assertTrue(service.isComplete(id));
    }

    @Test
    void isInProgressWhileRecordedButNotFullyApplied() {
        FakeStore store = new FakeStore();
        String id = InstanceMigrator.migrationId(task(OLD, NEW));
        store.recordExpected(id, Set.of("a", "b"));
        BroadcastMigrationService service = new BroadcastMigrationService("a",
                new InstanceMigrator("a", List.of(), store), store, new RecordingPublisher(), new FakeRegistry());

        store.markApplied(id, "a");
        assertTrue(service.isInProgress(id));

        store.markApplied(id, "b");
        assertFalse(service.isInProgress(id));
    }

    @Test
    void isNotInProgressForAnUnknownMigration() {
        FakeStore store = new FakeStore();
        BroadcastMigrationService service = new BroadcastMigrationService("a",
                new InstanceMigrator("a", List.of(), store), store, new RecordingPublisher(), new FakeRegistry());

        assertFalse(service.isInProgress("nope>nope"));
    }

    @Test
    void handleOpensTheBarrierNobodyElseCould() {
        // A migration published by Nyx carries no barrier: without this the network could never tell
        // that it finished, so isComplete would stay false for a migration that is fully applied.
        RecordingModule module = new RecordingModule();
        FakeStore store = new FakeStore();
        BroadcastMigrationService service = service(module, store, new RecordingPublisher());

        service.handle(task(OLD, NEW));

        String id = InstanceMigrator.migrationId(task(OLD, NEW));
        assertEquals(Set.of("inst-1"), store.expectedInstances(id));
        assertTrue(service.isComplete(id));
    }

    @Test
    void handleLeavesAnExistingBarrierAlone() {
        // Every instance receives the same broadcast; if each stamped its own view, the union could name
        // an instance that is never going to apply it and the migration would hang forever.
        FakeStore store = new FakeStore();
        String id = InstanceMigrator.migrationId(task(OLD, NEW));
        store.recordExpected(id, Set.of("proxy", "hub-1"));

        service(new RecordingModule(), store, new RecordingPublisher()).handle(task(OLD, NEW));

        assertEquals(Set.of("proxy", "hub-1"), store.expectedInstances(id));
    }

    @Test
    void statusNamesWhoIsStillHoldingTheMigrationOpen() {
        FakeStore store = new FakeStore();
        String id = InstanceMigrator.migrationId(task(OLD, NEW));
        store.record(task(OLD, NEW));
        store.recordExpected(id, Set.of("proxy", "hub-1"));
        store.markApplied(id, "proxy");
        BroadcastMigrationService service = new BroadcastMigrationService("proxy",
                new InstanceMigrator("proxy", List.of(), store), store, new RecordingPublisher(), new FakeRegistry());

        MigrationStatus status = service.status(OLD, NEW);

        assertEquals(Set.of("hub-1"), status.waitingOn());
        assertEquals("Notch", status.username());
        assertTrue(status.inProgress());
        assertFalse(status.complete());
    }

    @Test
    void inFlightListsTheUnfinishedAndDropsTheFinished() {
        FakeStore store = new FakeStore();
        UUID other = new UUID(0L, 3L);
        String stuck = InstanceMigrator.migrationId(task(OLD, NEW));
        String done = InstanceMigrator.migrationId(task(OLD, other));
        store.record(task(OLD, NEW));
        store.record(task(OLD, other));
        store.recordExpected(stuck, Set.of("proxy", "hub-1"));
        store.recordExpected(done, Set.of("proxy"));
        store.markApplied(stuck, "proxy");
        store.markApplied(done, "proxy");
        BroadcastMigrationService service = new BroadcastMigrationService("proxy",
                new InstanceMigrator("proxy", List.of(), store), store, new RecordingPublisher(), new FakeRegistry());

        List<MigrationStatus> inFlight = service.inFlight();

        assertEquals(1, inFlight.size());
        assertEquals(NEW, inFlight.get(0).to());
    }

    @Test
    void aMigrationWithNoBarrierIsNeverReportedComplete() {
        FakeStore store = new FakeStore();
        store.record(task(OLD, NEW));
        BroadcastMigrationService service = new BroadcastMigrationService("proxy",
                new InstanceMigrator("proxy", List.of(), store), store, new RecordingPublisher(), new FakeRegistry());

        MigrationStatus status = service.status(OLD, NEW);

        assertFalse(status.complete());
        assertFalse(status.inProgress());
    }
}
