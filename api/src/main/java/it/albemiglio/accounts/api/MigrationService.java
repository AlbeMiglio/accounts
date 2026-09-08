package it.albemiglio.accounts.api;

import java.util.List;
import java.util.UUID;

/**
 * The contract another plugin on the same proxy (e.g. Nyx) uses to trigger a migration in-process.
 * accounts' proxy plugin implements this; a caller looks the plugin up and calls {@link #migrate}.
 * Ship this jar as a {@code provided} dependency in the caller — never shade it, or the two copies
 * would have different classloader identity and the cast would fail.
 */
public interface MigrationService {

    /**
     * Migrate all data keyed by {@code from} to {@code to} across the network, atomically per
     * database and reliably (durable, idempotent, with offline catch-up). {@code username} is
     * metadata for the migration record.
     */
    void migrate(UUID from, UUID to, String username);

    /**
     * Whether a migration {@code from -> to} is started but not yet finished across the network. Nyx
     * blocks the player's premium login while this is true, so they can't re-enter mid-transfer.
     */
    boolean isMigrationInProgress(UUID from, UUID to);

    /**
     * A player kept their identity and changed their name — the other half of an identity change, and
     * the one that matters to any store still keyed by the name. Default no-op so a caller built
     * against a newer api than the accounts running beside it degrades to "renames are not applied"
     * instead of failing to link.
     */
    default void rename(UUID uuid, String oldName, String newName) {
    }

    /**
     * Where the migration {@code from -> to} has got to: who still has to apply it, who already has.
     * Never null — a migration nobody ever recorded comes back with empty sets, which reads as neither
     * complete nor in progress.
     */
    MigrationStatus migrationStatus(UUID from, UUID to);

    /**
     * Every migration that has been started and is not finished yet, most recent first. This is the
     * feed an admin view (or an operator debugging a stuck transfer) reads.
     */
    List<MigrationStatus> migrationsInFlight();
}
