package it.albemiglio.accounts.core.dashboard;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What the panel is allowed to do, as opposed to show. Kept behind its own interface and off by
 * default: everything else the dashboard serves is a read, and that is what makes a leaked token
 * survivable — with actions on, the same token moves player data.
 */
public interface DashboardActions {

    /** Actions off: the panel reads, and says so rather than pretending a button did something. */
    DashboardActions NONE = new DashboardActions() {
        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public void migrate(UUID from, UUID to, String username) {
            throw new UnsupportedOperationException("actions are off");
        }

        @Override
        public void rename(UUID uuid, String oldName, String newName) {
            throw new UnsupportedOperationException("actions are off");
        }

        @Override
        public Optional<UUID> premiumUuid(String username) {
            return Optional.empty();
        }

        @Override
        public List<it.albemiglio.accounts.core.services.RedisMigrationStore.Transfer> history(String who) {
            return Collections.emptyList();
        }

        @Override
        public java.util.Set<String> activeInstances() {
            return Collections.emptySet();
        }

        @Override
        public java.util.Map<String, String> progress(String migrationId) {
            return Collections.emptyMap();
        }

        @Override
        public java.util.Map<String, List<String>> diagnose(UUID probe) {
            return Collections.emptyMap();
        }
    };

    boolean enabled();

    /** Broadcasts a transfer. Idempotent per pair, so running it again is how a stuck one is retried. */
    void migrate(UUID from, UUID to, String username);

    /** Carries a name change to every store keyed by the name. */
    void rename(UUID uuid, String oldName, String newName);

    /** The Mojang identity behind a name, if the name is a premium account. */
    Optional<UUID> premiumUuid(String username);

    /**
     * Every transfer this name or identity has been part of. A read, unlike the rest of this
     * interface — it is here because the panel asks all of it about the same player at once.
     */
    List<it.albemiglio.accounts.core.services.RedisMigrationStore.Transfer> history(String nameOrUuid);

    /** Which servers have heartbeated recently — the ones a transfer will actually reach. */
    java.util.Set<String> activeInstances();

    /** Instance id to {@code "done/total@startedAt"} for a transfer still being applied. */
    java.util.Map<String, String> progress(String migrationId);

    /**
     * Asks every server where this player's data is. Read-only, and slow by nature: it waits on the
     * other servers' databases.
     */
    java.util.Map<String, List<String>> diagnose(UUID probe);
}
