package it.albemiglio.accounts.core.modules;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Turns every module's findings for one probe player into the lines an operator reads before running a
 * migration: a count per verdict, then the blockers spelled out. Shared so the proxy and the backends
 * answer {@code /accounts diagnose} identically — the proxy holds the network-wide modules (permissions,
 * bans, skins), so it is the one that most needs asking.
 */
public final class DiagnosisReport {

    private DiagnosisReport() {
    }

    /** Runs the read-only probe. Blocks on the modules' databases: call it off the main thread. */
    public static List<String> of(Collection<Module> modules, UUID probe) {
        List<String> flagged = new ArrayList<>();
        int verified = 0;
        int blockers = 0;
        int scanAll = 0;
        int empty = 0;
        for (Module module : modules) {
            for (Diagnosis d : module.diagnose(probe)) {
                switch (d.getStatus()) {
                    case VERIFIED:
                        verified++;
                        break;
                    case INFO:
                        scanAll++;
                        break;
                    case NOT_FOUND:
                        empty++;
                        break;
                    default: // FORMAT_MISMATCH, MISSING, ERROR
                        blockers++;
                        flagged.add("  ⚠ " + d.line());
                }
            }
        }
        List<String> lines = new ArrayList<>();
        lines.add("Diagnosis vs " + probe + ": " + verified + " verified, " + blockers + " to FIX, "
                + scanAll + " scan-all, " + empty + " with no data for this player.");
        lines.addAll(flagged);
        lines.add(blockers == 0
                ? "✓ Looks safe — every module found this player's data in the expected encoding (or has none). Back up first anyway."
                : "⚠ Fix the flagged modules (wrong 'format', or a missing table/path) before migrating.");
        return lines;
    }
}
