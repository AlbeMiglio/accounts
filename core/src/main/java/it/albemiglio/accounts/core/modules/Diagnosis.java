package it.albemiglio.accounts.core.modules;

import lombok.Getter;

/**
 * One read-only finding from {@code /accounts diagnose <probe-uuid>}: for a single module target
 * (a table+column, a file, a text target), did a real player's uuid turn up where the module expects
 * it, and in the encoding the module assumes? This is what lets an operator confirm a migration is
 * safe <em>before</em> running it — in particular {@link Status#FORMAT_MISMATCH} catches a template
 * whose {@code format} (dashed/undashed/binary) doesn't match how the plugin actually stores the uuid,
 * which a blind migration would silently miss.
 */
@Getter
public final class Diagnosis {

    public enum Status {
        /** The probe uuid was found at this target in exactly the configured encoding. */
        VERIFIED,
        /** Found, but stored in a DIFFERENT encoding than the template configures — fix before migrating. */
        FORMAT_MISMATCH,
        /** Not found here: either this player has no data at this target, or the target is wrong. */
        NOT_FOUND,
        /** The table/column/path the module targets does not exist — the template is misconfigured. */
        MISSING,
        /** Probing the target failed. */
        ERROR,
        /**
         * The database belongs to a plugin that has it open — an embedded H2 or SQLite file is exclusive
         * to whichever process opened it. Nothing to fix: the migration reaches it at the next start,
         * where accounts runs before any plugin has taken a file.
         */
        HELD_BY_PLUGIN,
        /** Nothing to verify: a scan-all module (world NBT / vanilla JSON) matches every encoding by design. */
        INFO
    }

    private final String module;
    private final String location;
    private final Status status;
    private final int occurrences;
    private final String detail;

    private Diagnosis(String module, String location, Status status, int occurrences, String detail) {
        this.module = module;
        this.location = location;
        this.status = status;
        this.occurrences = occurrences;
        this.detail = detail;
    }

    public static Diagnosis verified(String module, String location, int occurrences) {
        return new Diagnosis(module, location, Status.VERIFIED, occurrences, "found in the expected encoding");
    }

    public static Diagnosis formatMismatch(String module, String location, String configured, String actual) {
        return new Diagnosis(module, location, Status.FORMAT_MISMATCH, 0,
                "stored as " + actual + " but the template configures " + configured + " — fix 'format' first");
    }

    public static Diagnosis notFound(String module, String location) {
        return new Diagnosis(module, location, Status.NOT_FOUND, 0,
                "no data for this player here (or the player is new)");
    }

    public static Diagnosis missing(String module, String location, String why) {
        return new Diagnosis(module, location, Status.MISSING, 0, why);
    }

    public static Diagnosis error(String module, String location, String why) {
        return new Diagnosis(module, location, Status.ERROR, 0, why);
    }

    /**
     * An embedded database the owning plugin holds open. Read as a failure it is a false alarm an
     * operator soon learns to ignore, which is exactly what a pre-flight cannot afford.
     */
    public static Diagnosis heldByPlugin(String module, String location, String why) {
        return new Diagnosis(module, location, Status.HELD_BY_PLUGIN, 0,
                "held open by the plugin that owns it; migrated at the next server start (" + why + ")");
    }

    public static Diagnosis info(String module, String location, String note) {
        return new Diagnosis(module, location, Status.INFO, 0, note);
    }

    /** A finding an operator must resolve before migrating (a mismatch or a broken target). */
    public boolean isBlocker() {
        return status == Status.FORMAT_MISMATCH || status == Status.MISSING || status == Status.ERROR;
    }

    /** One human-readable line for the command output. */
    public String line() {
        String count = occurrences > 0 ? " (" + occurrences + ")" : "";
        return "[" + module + "] " + location + " — " + status + count + ": " + detail;
    }
}
