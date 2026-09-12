package it.albemiglio.accounts.core.services;

import java.util.Map;

/**
 * How far through its own modules a server is, while it is still working. Applied-or-not is all the
 * completion barrier needs, but it is not what somebody watching a transfer wants to know: on a network
 * whose world scans take seconds, a server is "not done" for a long time and that says nothing about
 * whether it is moving or stuck.
 */
public interface MigrationProgress {

    MigrationProgress NONE = new MigrationProgress() {
        @Override
        public void report(String migrationId, String instanceId, int done, int total) {
        }

        @Override
        public Map<String, String> of(String migrationId) {
            return java.util.Collections.emptyMap();
        }
    };

    /** Called as each module finishes. {@code done} counts the modules run, {@code total} those to run. */
    void report(String migrationId, String instanceId, int done, int total);

    /** Instance id to {@code "done/total"}, for the servers currently working on this migration. */
    Map<String, String> of(String migrationId);
}
