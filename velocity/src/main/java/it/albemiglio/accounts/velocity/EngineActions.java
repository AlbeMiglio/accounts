package it.albemiglio.accounts.velocity;

import it.albemiglio.accounts.core.dashboard.DashboardActions;
import it.albemiglio.accounts.core.dashboard.MojangNames;
import it.albemiglio.accounts.core.services.AccountsEngine;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The panel's buttons, wired to the same engine the command drives — one path, not two.
 *
 * <p>Always installed, with {@code enabled} carrying whether the operator turned actions on: the reads
 * this interface also serves (a player's history, the identity behind a name) are what a read-only
 * panel is FOR, and handing it a no-op would have emptied the one screen that matters.
 */
final class EngineActions implements DashboardActions {

    private final AccountsEngine engine;
    private final boolean actions;

    EngineActions(AccountsEngine engine, boolean actions) {
        this.engine = engine;
        this.actions = actions;
    }

    @Override
    public boolean enabled() {
        return actions;
    }

    @Override
    public void migrate(UUID from, UUID to, String username) {
        engine.migrate(from, to, username);
    }

    @Override
    public void rename(UUID uuid, String oldName, String newName) {
        engine.rename(uuid, oldName, newName);
    }

    @Override
    public Optional<UUID> premiumUuid(String username) {
        return MojangNames.premiumUuid(username);
    }

    @Override
    public List<it.albemiglio.accounts.core.services.RedisMigrationStore.Transfer> history(String who) {
        return engine.history(who);
    }

    @Override
    public java.util.Set<String> activeInstances() {
        return engine.activeInstances();
    }

    @Override
    public java.util.Map<String, String> progress(String migrationId) {
        return engine.progress(migrationId);
    }
}
