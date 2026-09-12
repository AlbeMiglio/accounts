package it.albemiglio.accounts.core.dashboard;

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
    };

    boolean enabled();

    /** Broadcasts a transfer. Idempotent per pair, so running it again is how a stuck one is retried. */
    void migrate(UUID from, UUID to, String username);

    /** Carries a name change to every store keyed by the name. */
    void rename(UUID uuid, String oldName, String newName);

    /** The Mojang identity behind a name, if the name is a premium account. */
    Optional<UUID> premiumUuid(String username);
}
