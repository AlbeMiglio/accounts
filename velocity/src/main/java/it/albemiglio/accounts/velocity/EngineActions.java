package it.albemiglio.accounts.velocity;

import it.albemiglio.accounts.core.dashboard.DashboardActions;
import it.albemiglio.accounts.core.dashboard.MojangNames;
import it.albemiglio.accounts.core.services.AccountsEngine;

import java.util.Optional;
import java.util.UUID;

/** The panel's buttons, wired to the same engine the command drives — one path, not two. */
final class EngineActions implements DashboardActions {

    private final AccountsEngine engine;

    EngineActions(AccountsEngine engine) {
        this.engine = engine;
    }

    @Override
    public boolean enabled() {
        return true;
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
}
