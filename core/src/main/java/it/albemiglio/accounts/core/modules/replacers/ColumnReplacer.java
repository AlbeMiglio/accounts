package it.albemiglio.accounts.core.modules.replacers;

import it.albemiglio.accounts.core.modules.Diagnosis;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Logger;

public class ColumnReplacer extends Replacer {

    private static final Logger LOG = Logger.getLogger(ColumnReplacer.class.getName());

    private final String table;
    private final String tablePattern;
    private final String column;
    private final UuidCodec codec;
    private final String prefix;
    private final List<Derived> derived;

    public ColumnReplacer(String table, String column) {
        this(table, column, UuidCodec.DASHED);
    }

    public ColumnReplacer(String table, String column, UuidCodec codec) {
        this(table, null, column, codec, null, Collections.<Derived>emptyList());
    }

    /**
     * @param table        the target table, or null when {@code tablePattern} drives discovery
     * @param tablePattern a SQL LIKE pattern resolved against the live schema at execute time, for
     *                     plugins that create one table per board/world/group (ajLeaderboards,
     *                     BetterRTP, BetterEnderChest). Exactly one of {@code table}/{@code tablePattern}
     *                     is set. The pattern matches the identifiers the engine actually stores: H2
     *                     upper-cases unquoted identifiers on creation (unless the database was created
     *                     with different case flags), so give the pattern in that stored case.
     * @param prefix       literal prepended to the encoded uuid in the stored value (AuxProtect stores
     *                     {@code '$' + dashed}); matching and rewriting both use prefix + encoding
     * @param derived      extra columns rewritten by the same UPDATE, computed from the new stored string
     */
    public ColumnReplacer(String table, String tablePattern, String column, UuidCodec codec,
                          String prefix, List<Derived> derived) {
        if ((table == null) == (tablePattern == null)) {
            throw new IllegalArgumentException(
                    "a replacer needs exactly one of 'table' and 'table-pattern'");
        }
        this.prefix = prefix == null ? "" : prefix;
        if (codec == UuidCodec.BINARY && (!this.prefix.isEmpty() || !derived.isEmpty())) {
            throw new IllegalArgumentException("'prefix' and 'derived' work on the stored string form, "
                    + "so they cannot be combined with format: binary");
        }
        this.table = table;
        this.tablePattern = tablePattern;
        this.column = column;
        this.codec = codec;
        this.derived = derived;
    }

    @Override
    public void replace(Connection connection, UUID oldId, UUID newId) throws SQLException {
        if (tablePattern == null) {
            update(connection, table, oldId, newId);
            return;
        }
        for (String matched : matchingTables(connection)) {
            // Heterogeneous tables may share the prefix (a per-board data table next to its metadata
            // table), so a matched table without the column is skipped instead of failing the module.
            if (hasColumn(connection, matched)) {
                update(connection, matched, oldId, newId);
            } else {
                LOG.info("table " + matched + " matches '" + tablePattern + "' but has no column "
                        + column + " — skipped");
            }
        }
    }

    private void update(Connection connection, String target, UUID oldId, UUID newId) throws SQLException {
        StringBuilder sql = new StringBuilder("UPDATE ").append(target)
                .append(" SET ").append(column).append(" = ?");
        for (Derived extra : derived) {
            sql.append(", ").append(extra.column).append(" = ?");
        }
        sql.append(" WHERE ").append(column).append(" = ?");
        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            if (codec == UuidCodec.BINARY) {
                codec.bind(ps, 1, newId);
                codec.bind(ps, 2, oldId);
            } else {
                String stored = prefix + codec.encode(newId);
                int index = 1;
                ps.setString(index++, stored);
                for (Derived extra : derived) {
                    ps.setInt(index++, extra.apply(stored));
                }
                ps.setString(index, prefix + codec.encode(oldId));
            }
            ps.executeUpdate();
        }
    }

    /** The live user tables the pattern matches; SQLite's internal {@code sqlite_*} tables never qualify. */
    private List<String> matchingTables(Connection connection) throws SQLException {
        List<String> tables = new ArrayList<>();
        DatabaseMetaData meta = connection.getMetaData();
        try (ResultSet rs = meta.getTables(connection.getCatalog(), null, tablePattern,
                new String[]{"TABLE"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (!name.regionMatches(true, 0, "sqlite_", 0, 7)) {
                    tables.add(name);
                }
            }
        }
        return tables;
    }

    private boolean hasColumn(Connection connection, String target) throws SQLException {
        // Compare against the bare identifier: sql-module templates may quote the column name.
        String bare = column.replace("\"", "").replace("`", "");
        try (ResultSet rs = connection.getMetaData().getColumns(connection.getCatalog(), null, target, null)) {
            while (rs.next()) {
                if (rs.getString("COLUMN_NAME").equalsIgnoreCase(bare)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Read-only: for each target table, is the probe uuid present in {@code column}, and in which
     * encoding? Reports VERIFIED when it's stored exactly as this replacer configures, FORMAT_MISMATCH
     * when it's really stored in a different encoding (the migration would miss it), NOT_FOUND when the
     * player simply has no row here, or MISSING when the table/column doesn't exist.
     */
    @Override
    public List<Diagnosis> diagnose(Connection connection, UUID probe, String module) {
        List<String> targets;
        try {
            targets = tablePattern == null ? Collections.singletonList(table) : matchingTables(connection);
        } catch (SQLException e) {
            return Collections.singletonList(Diagnosis.error(module, patternLabel(), e.getMessage()));
        }
        if (targets.isEmpty()) {
            return Collections.singletonList(
                    Diagnosis.missing(module, patternLabel(), "no table matches '" + tablePattern + "'"));
        }
        List<Diagnosis> out = new ArrayList<>();
        for (String target : targets) {
            out.add(diagnoseTable(connection, target, probe, module));
        }
        return out;
    }

    private Diagnosis diagnoseTable(Connection connection, String target, UUID probe, String module) {
        String location = target + "." + column;
        try {
            UuidCodec firstMatch = null;
            int configuredCount = 0;
            for (UuidCodec codec : UuidCodec.values()) {
                // prefix + binary is impossible, so a binary column is never the stored form when a prefix is set.
                if (codec == UuidCodec.BINARY && !prefix.isEmpty()) {
                    continue;
                }
                int n;
                try {
                    n = count(connection, target, codec, probe);
                } catch (SQLException e) {
                    // A codec the column cannot even be compared against is not a finding: MySQL rejects
                    // raw bytes against a utf8 text column, which only proves binary isn't the stored
                    // form. Only the configured codec failing means the template points at nothing.
                    if (codec == this.codec) {
                        throw e;
                    }
                    continue;
                }
                if (codec == this.codec) {
                    configuredCount = n;
                }
                if (n > 0 && firstMatch == null) {
                    firstMatch = codec;
                }
            }
            if (configuredCount > 0) {
                return Diagnosis.verified(module, location, configuredCount);
            }
            if (firstMatch != null) {
                return Diagnosis.formatMismatch(module, location, formatLabel(this.codec),
                        firstMatch.name().toLowerCase(Locale.ROOT));
            }
            return Diagnosis.notFound(module, location);
        } catch (SQLException e) {
            // the table or column doesn't exist as the template configures it
            return Diagnosis.missing(module, location, "not queryable: " + e.getMessage());
        }
    }

    private int count(Connection connection, String target, UuidCodec codec, UUID probe) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + target + " WHERE " + column + " = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (codec == UuidCodec.BINARY) {
                codec.bind(ps, 1, probe);
            } else {
                ps.setString(1, prefix + codec.encode(probe));
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private String formatLabel(UuidCodec codec) {
        String base = codec.name().toLowerCase(Locale.ROOT);
        return prefix.isEmpty() ? base : "prefix \"" + prefix + "\" + " + base;
    }

    private String patternLabel() {
        return "tables '" + tablePattern + "'." + column;
    }

    /** An extra column rewritten by the same UPDATE, computed from the NEW stored string. */
    public static final class Derived {

        /** {@code INT} column holding {@code String.hashCode()} of the full stored string (prefix + uuid). */
        public static final String JAVA_STRING_HASHCODE = "java-string-hashcode";

        private final String column;

        public Derived(String column, String fn) {
            if (!JAVA_STRING_HASHCODE.equals(fn)) {
                throw new IllegalArgumentException("unknown derived fn \"" + fn + "\" — only \""
                        + JAVA_STRING_HASHCODE + "\" is supported");
            }
            this.column = column;
        }

        int apply(String stored) {
            return stored.hashCode();
        }
    }
}
