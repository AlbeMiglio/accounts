package it.albemiglio.accounts.core.services;

import it.albemiglio.accounts.core.objects.Task;

/** Broadcasts a migration to every other accounts instance (Redis pub/sub in production). */
public interface MigrationPublisher {

    void publish(Task task);

    /** Renames travel on the same channel; the wire form tells them apart. */
    default void publish(it.albemiglio.accounts.core.objects.Rename rename) {
    }
}
