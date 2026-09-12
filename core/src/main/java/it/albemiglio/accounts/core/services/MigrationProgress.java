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
        public void report(String migrationId, String instanceId, int done, int total, long startedAt) {
        }

        @Override
        public Map<String, String> of(String migrationId) {
            return java.util.Collections.emptyMap();
        }
    };

    /**
     * Called as each module finishes. {@code done} counts the modules run, {@code total} those to run,
     * and {@code startedAt} is when this server began — without it there is no telling a server that is
     * working from one that is dragging, since the modules are nothing like equal in cost.
     */
    void report(String migrationId, String instanceId, int done, int total, long startedAt);

    /** Instance id to {@code "done/total@startedAt"}, for the servers working on this migration. */
    Map<String, String> of(String migrationId);
}
