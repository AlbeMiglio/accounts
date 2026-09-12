package it.albemiglio.accounts.core.services;

/**
 * Where a migration spent its time. Recorded per module and per instance as it runs, so an operator can
 * answer "how long does a transfer take here, and what is the slow part" from measurements rather than
 * from a stopwatch and a guess — the world modules dominate on one network and the databases on another,
 * and only the numbers say which.
 */
public interface MigrationTimings {

    MigrationTimings NONE = new MigrationTimings() {
        @Override
        public void module(String instanceId, String module, long millis, boolean ok) {
        }

        @Override
        public void migration(String migrationId, String instanceId, long millis, int modules) {
        }
    };

    /** One module finished on this instance, successfully or not. */
    void module(String instanceId, String module, long millis, boolean ok);

    /** Every module of one migration finished on this instance. */
    void migration(String migrationId, String instanceId, long millis, int modules);
}
