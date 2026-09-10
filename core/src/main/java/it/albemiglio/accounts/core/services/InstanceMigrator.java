package it.albemiglio.accounts.core.services;

import it.albemiglio.accounts.core.modules.Module;
import it.albemiglio.accounts.core.objects.Rename;
import it.albemiglio.accounts.core.objects.Task;

import java.util.Collection;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Applies a migration to one instance's local modules. Idempotent: if the migration is already
 * recorded as applied for this instance it does nothing, so a retry or a duplicate broadcast is safe.
 * Marks the migration applied only when every enabled module succeeded; a partial failure is recorded
 * as failed and left un-applied, so it will be retried (the column relabel itself is idempotent).
 */
public final class InstanceMigrator {

    private static final Logger LOG = Logger.getLogger(InstanceMigrator.class.getName());

    private final String instanceId;
    private final Collection<Module> modules;
    private final MigrationLog log;

    public InstanceMigrator(String instanceId, Collection<Module> modules, MigrationLog log) {
        this.instanceId = instanceId;
        this.modules = modules;
        this.log = log;
    }

    public void apply(Task task) {
        String id = migrationId(task);
        if (log.hasApplied(id, instanceId)) {
            return;
        }
        boolean anyFailed = false;
        for (Module module : modules) {
            if (!module.isEnabled()) {
                continue;
            }
            try {
                module.execute(task.getMigration());
            } catch (RuntimeException e) {
                // Any module failure (a MigrationException, or an unexpected one like a driver/pool
                // error) is recorded and retried later, never propagated to crash the caller. Logged
                // because the retry is the next restart: silence here is data that looks migrated.
                LOG.warning("module " + module.getName() + " failed migration " + id + ", will retry: " + e);
                anyFailed = true;
            }
        }
        if (anyFailed) {
            log.markFailed(id, instanceId);
        } else {
            log.markApplied(id, instanceId);
        }
    }

    /** Same contract as {@link #apply(Task)}: idempotent, all-or-marked-failed, never throws. */
    public void apply(Rename rename) {
        if (log.hasApplied(rename.id(), instanceId)) {
            return;
        }
        boolean anyFailed = false;
        for (Module module : modules) {
            if (!module.isEnabled()) {
                continue;
            }
            try {
                module.rename(rename);
            } catch (RuntimeException e) {
                LOG.warning("module " + module.getName() + " failed rename " + rename.id() + ", will retry: " + e);
                anyFailed = true;
            }
        }
        if (anyFailed) {
            log.markFailed(rename.id(), instanceId);
        } else {
            log.markApplied(rename.id(), instanceId);
        }
    }

    public static String migrationId(Task task) {
        return migrationId(task.getMigration().getLeft(), task.getMigration().getRight());
    }

    public static String migrationId(UUID from, UUID to) {
        return from + ">" + to;
    }
}
