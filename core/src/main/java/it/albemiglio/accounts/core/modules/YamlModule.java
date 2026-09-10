package it.albemiglio.accounts.core.modules;

import it.albemiglio.accounts.core.database.DB;
import it.albemiglio.accounts.core.modules.replacers.NameReplacer;
import it.albemiglio.accounts.core.modules.replacers.Replacer;
import it.albemiglio.accounts.core.objects.Rename;
import it.albemiglio.accounts.core.objects.enums.Platform;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class YamlModule extends Module {

    public YamlModule(String name, Platform platform, DB database, Collection<Replacer> replacers) {
        this(name, platform, database, replacers, false);
    }

    private final List<NameReplacer> nameReplacers = new ArrayList<>();
    private final DB database;

    public YamlModule(String name, Platform platform, DB database, Collection<Replacer> replacers,
                      boolean disableForeignKeyChecks) {
        this(name, platform, database, replacers, disableForeignKeyChecks, java.util.Collections.emptyList());
    }

    public YamlModule(String name, Platform platform, DB database, Collection<Replacer> replacers,
                      boolean disableForeignKeyChecks, Collection<NameReplacer> nameReplacers) {
        super(name, platform, database);
        this.database = database;
        replacers.forEach(this::addReplacer);
        this.nameReplacers.addAll(nameReplacers);
        if (disableForeignKeyChecks) {
            disableForeignKeyChecks();
        }
    }

    /**
     * Applies a name change to the columns this module declared. One transaction, so a store that keeps
     * the name in several columns can never end up half-renamed; a module with nothing declared does
     * nothing, which is the normal case.
     */
    @Override
    public void rename(Rename rename) {
        if (nameReplacers.isEmpty() || database == null) {
            return;
        }
        try (Connection connection = database.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                for (NameReplacer replacer : nameReplacers) {
                    replacer.rename(connection, rename);
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw new MigrationException("Rename failed for module " + getName(), e);
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new MigrationException("Rename failed for module " + getName(), e);
        }
    }
}
