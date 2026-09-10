package it.albemiglio.accounts.core.modules;

import it.albemiglio.accounts.core.database.DB;
import it.albemiglio.accounts.core.objects.enums.Platform;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A database this server cannot open — an embedded file the owning plugin holds a lock on, a bad path —
 * surfaces as Hikari's PoolInitializationException, a RuntimeException. It must be reported as that one
 * module's ERROR, not abort the diagnosis of every module after it.
 */
class DiagnoseFailureTest {

    private static final class UnopenableDb extends DB {
        UnopenableDb() {
            super("", 0, null, null, "locked.db");
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
            throw new IllegalStateException("Database may be already in use");
        }
    }

    @Test
    void reportsAnUnopenableDatabaseInsteadOfThrowing() {
        Module module = new Module("locked", Platform.SPIGOT, new UnopenableDb()) { };

        List<Diagnosis> report = module.diagnose(UUID.randomUUID());

        assertEquals(1, report.size());
        assertEquals(Diagnosis.Status.ERROR, report.get(0).getStatus());
        assertTrue(report.get(0).getDetail().contains("already in use"));
    }
}
