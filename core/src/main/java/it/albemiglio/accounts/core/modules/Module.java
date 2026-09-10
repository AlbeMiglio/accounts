package it.albemiglio.accounts.core.modules;

import it.albemiglio.accounts.core.database.DB;
import it.albemiglio.accounts.core.modules.replacers.Replacer;
import it.albemiglio.accounts.core.objects.Pair;
import it.albemiglio.accounts.core.objects.enums.DBType;
import it.albemiglio.accounts.core.objects.enums.Platform;
import lombok.Getter;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.logging.Logger;

public abstract class Module {

    private static final Logger LOG = Logger.getLogger(Module.class.getName());

    @Getter
    private final String name;
    private final Platform platform;
    private boolean running;
    @Getter
    private boolean enabled;
    private boolean foreignKeyChecksDisabled;
    private Optional<String> pluginName;

    private final DB database;
    private final Set<Replacer> replacers;

    public Module(String name, Platform platform) {
        this(name, platform, null);
    }

    public Module(String name, Platform platform, DB database) {
        this.name = name;
        this.platform = platform;
        this.database = database;
        this.running = false;
        this.enabled = false;
        this.pluginName = Optional.empty();
        this.replacers = new LinkedHashSet<>();
    }

    /**
     * Applies a name change to this module's store. Does nothing by default: almost every plugin keys
     * its data by uuid, and for those a rename is a non-event. Only a module whose store is keyed by
     * the player's name — AuthMe is the case that forced this — overrides it.
     */
    public void rename(it.albemiglio.accounts.core.objects.Rename rename) {
    }

    protected void addReplacer(Replacer replacer) {
        this.replacers.add(replacer);
    }

    /**
     * Plugins like HuskSync and BattlePass declare foreign keys between their uuid columns without
     * ON UPDATE CASCADE, so whichever table a replacer updates first violates the constraint. When set,
     * enforcement is suspended around this module's transaction (MySQL/MariaDB
     * {@code FOREIGN_KEY_CHECKS}, H2 {@code SET REFERENTIAL_INTEGRITY}) and restored afterwards even on
     * rollback; engines with nothing to suspend (SQLite doesn't enforce foreign keys by default) log
     * and ignore the flag.
     */
    protected void disableForeignKeyChecks() {
        this.foreignKeyChecksDisabled = true;
    }

    public void enable() {
        this.enabled = true;
    }

    public void disable() {
        this.enabled = false;
    }

    public void reload() {}

    public void execute(Pair<UUID, UUID> migration) {
        try (Connection connection = database.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            String suspend = foreignKeyChecksDisabled
                    ? foreignKeyChecksSql(database.getType(), false) : null;
            if (foreignKeyChecksDisabled && suspend == null) {
                LOG.info("module " + name + ": disable-foreign-key-checks ignored on "
                        + database.getType() + " — nothing to suspend");
            }
            if (suspend != null) {
                executeStatement(connection, suspend);
            }
            try {
                for (Replacer replacer : replacers) {
                    replacer.replace(connection, migration.getLeft(), migration.getRight());
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw new MigrationException("Migration failed for module " + name, e);
            } finally {
                if (suspend != null) {
                    // Restore even on rollback: MySQL scopes the flag to the session and this
                    // connection goes back to the pool; H2 scopes it to the whole database.
                    try {
                        executeStatement(connection, foreignKeyChecksSql(database.getType(), true));
                    } catch (SQLException e) {
                        LOG.warning("module " + name + " could not restore foreign key checks: " + e);
                    }
                }
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new MigrationException("Migration failed for module " + name, e);
        }
    }

    /**
     * Read-only pre-flight for {@code /accounts diagnose <probe-uuid>}: report — without writing anything —
     * whether this player's data is where each replacer expects it and in the assumed encoding. SQL modules
     * probe their columns (and catch a wrong {@code format}); file/content/world modules override this.
     * Lets an operator confirm a migration is safe before running it.
     */
    public List<Diagnosis> diagnose(UUID probe) {
        List<Diagnosis> report = new ArrayList<>();
        if (database == null) {
            return report;
        }
        try (Connection connection = database.getConnection()) {
            for (Replacer replacer : replacers) {
                report.addAll(replacer.diagnose(connection, probe, name));
            }
        } catch (SQLException | RuntimeException e) {
            // Not just SQLException: an unopenable file surfaces as Hikari's PoolInitializationException
            // and one bad module must not abort the diagnosis of every module after it.
            report.add(Diagnosis.error(name, "database", "cannot open: " + e.getMessage()));
        }
        return report;
    }

    /**
     * The engine's toggle statement, or null where there is no enforcement to toggle. H2's
     * {@code SET REFERENTIAL_INTEGRITY} needs admin rights and commits the open transaction, which is
     * why it only ever runs before the first replacer and after commit/rollback.
     */
    private static String foreignKeyChecksSql(DBType type, boolean enabled) {
        if (type == DBType.MYSQL || type == DBType.MARIADB) {
            return "SET FOREIGN_KEY_CHECKS=" + (enabled ? 1 : 0);
        }
        if (type == DBType.H2) {
            return "SET REFERENTIAL_INTEGRITY " + (enabled ? "TRUE" : "FALSE");
        }
        return null;
    }

    private static void executeStatement(Connection connection, String sql) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute(sql);
        }
    }

    public void executeBatch(Collection<Pair<UUID, UUID>> tasks) {
        tasks.forEach(this::execute);
    }
}
