package it.albemiglio.accounts.core.services;

import it.albemiglio.accounts.api.MigrationStatus;
import it.albemiglio.accounts.core.objects.Rename;
import it.albemiglio.accounts.core.objects.Task;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Drives migrations in the broadcast model: every accounts instance runs one of these. Initiating a
 * migration records it durably, applies it locally, and broadcasts it; receiving one applies it; and
 * on startup {@link #recoverPending()} replays anything this instance missed while it was down. Apply
 * is idempotent, so the overlap between broadcast delivery and recovery is harmless.
 */
public final class BroadcastMigrationService {

    private final String instanceId;
    private final InstanceMigrator migrator;
    private final MigrationStore store;
    private final MigrationPublisher publisher;
    private final InstanceRegistry registry;

    public BroadcastMigrationService(String instanceId, InstanceMigrator migrator, MigrationStore store,
                                     MigrationPublisher publisher, InstanceRegistry registry) {
        this.instanceId = instanceId;
        this.migrator = migrator;
        this.store = store;
        this.publisher = publisher;
        this.registry = registry;
    }

    /**
     * Entry point for a new migration (admin command or Nyx): snapshot who must apply it (the barrier),
     * record it, apply it here, and broadcast it to the rest.
     */
    public void migrate(Task task) {
        store.recordExpected(InstanceMigrator.migrationId(task), registry.activeInstances());
        store.record(task);
        migrator.apply(task);
        publisher.publish(task);
    }

    /** A name change: recorded, applied here, broadcast — the same path a uuid migration takes. */
    public void rename(Rename rename) {
        store.recordExpected(rename.id(), registry.activeInstances());
        store.record(rename);
        migrator.apply(rename);
        publisher.publish(rename);
    }

    /** A rename broadcast by another instance (or by Nyx). */
    public void handle(Rename rename) {
        store.record(rename);
        store.recordExpectedIfAbsent(rename.id(), registry.activeInstances());
        migrator.apply(rename);
    }

    /** Whether every instance expected to apply this migration has done so (Nyx's unlock gate). */
    public boolean isComplete(String migrationId) {
        Set<String> expected = store.expectedInstances(migrationId);
        return !expected.isEmpty() && store.appliedInstances(migrationId).containsAll(expected);
    }

    /** Whether this migration was started but isn't done yet — Nyx blocks the player's login while true. */
    public boolean isInProgress(String migrationId) {
        Set<String> expected = store.expectedInstances(migrationId);
        return !expected.isEmpty() && !store.appliedInstances(migrationId).containsAll(expected);
    }

    /**
     * A migration broadcast by another instance (or by Nyx) arrived. Records it too, so durability does
     * not depend on who published it: any online instance persists it for the offline ones. It also
     * opens the completion barrier if the publisher could not — Nyx publishes over Redis without any
     * view of who must apply, and without a barrier the migration would be tracked as neither in
     * progress nor complete, forever.
     */
    public void handle(Task task) {
        String migrationId = InstanceMigrator.migrationId(task);
        store.record(task);
        store.recordExpectedIfAbsent(migrationId, registry.activeInstances());
        migrator.apply(task);
    }

    /** Where one migration has got to; empty sets when nothing was ever recorded for it. */
    public MigrationStatus status(UUID from, UUID to) {
        String migrationId = InstanceMigrator.migrationId(from, to);
        Task recorded = null;
        for (Task task : store.all()) {
            if (InstanceMigrator.migrationId(task).equals(migrationId)) {
                recorded = task;
                break;
            }
        }
        return new MigrationStatus(from, to, recorded == null ? "" : recorded.getUsername(),
                store.expectedInstances(migrationId), store.appliedInstances(migrationId));
    }

    /** Every recorded migration that has not finished across the network yet. */
    public List<MigrationStatus> inFlight() {
        List<MigrationStatus> out = new ArrayList<>();
        for (Task task : store.all()) {
            MigrationStatus status = status(task.getMigration().getLeft(), task.getMigration().getRight());
            if (!status.complete()) {
                out.add(status);
            }
        }
        return out;
    }

    /** Apply everything this instance still owes (called on startup, idempotent). */
    public void recoverPending() {
        for (Task task : store.pending(instanceId)) {
            migrator.apply(task);
        }
        for (Rename rename : store.pendingRenames(instanceId)) {
            migrator.apply(rename);
        }
    }
}
