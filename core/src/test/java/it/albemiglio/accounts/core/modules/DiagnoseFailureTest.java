package it.albemiglio.accounts.core.modules;

import it.albemiglio.accounts.core.database.DB;
import it.albemiglio.accounts.core.objects.enums.Platform;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A database this server cannot open surfaces as Hikari's PoolInitializationException, a RuntimeException:
 * it must be reported as that one module's finding, not abort the diagnosis of every module after it.
 * And an embedded file the owning plugin simply has open is not a fault to fix — accounts reaches it at
 * the next start, before any plugin has taken a file — so it must not read as one.
 */
class DiagnoseFailureTest {

    private static final class UnopenableDb extends DB {
        private final RuntimeException failure;

        UnopenableDb(RuntimeException failure) {
            super("", 0, null, null, "locked.db");
            this.failure = failure;
        }

        @Override
        public String jdbcUrl() {
            return "jdbc:none";
        }

        @Override
        public String driverClassName() {
            return "none";
        }

        @Override
        public Connection getConnection() {
            throw failure;
        }
    }

    private static Diagnosis diagnose(RuntimeException failure) {
        Module module = new Module("mod", Platform.SPIGOT, new UnopenableDb(failure)) { };
        List<Diagnosis> report = module.diagnose(UUID.randomUUID());
        assertEquals(1, report.size());
        return report.get(0);
    }

    @Test
    void aFileTheOwningPluginHasOpenIsNotSomethingToFix() {
        Diagnosis d = diagnose(new IllegalStateException(
                "Database may be already in use: \"graves.data.mv.db\" [90020-232]"));

        assertEquals(Diagnosis.Status.HELD_BY_PLUGIN, d.getStatus());
        assertFalse(d.isBlocker());
    }

    /** A shaded H2 cannot always load its own message bundle, so only the code comes through. */
    @Test
    void theH2CodeAloneIsEnoughToRecogniseIt() {
        assertEquals(Diagnosis.Status.HELD_BY_PLUGIN,
                diagnose(new IllegalStateException("(Message 90020 not found) [90020-232]")).getStatus());
    }

    @Test
    void anythingElseIsReportedAsAFaultInsteadOfThrowing() {
        Diagnosis d = diagnose(new IllegalStateException("path to 'plugins/X/data.db' does not exist"));

        assertEquals(Diagnosis.Status.ERROR, d.getStatus());
        assertTrue(d.isBlocker());
        assertTrue(d.getDetail().contains("does not exist"));
    }
}
