package it.albemiglio.accounts.core.modules.replacers;

import it.albemiglio.accounts.core.modules.Diagnosis;
import it.albemiglio.accounts.core.objects.Rename;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Follows a player through a name change in a store keyed by their name. One row, one statement: find
 * it by the name it is filed under and write the new one — plus, where the store keeps the identity in
 * a column, the uuid, because a rename is the moment that column can be trusted.
 *
 * <p>AuthMe is the shape this was written for: the key is {@code username} in lower case, the display
 * form lives in {@code realname}, and {@code premiumUUID} holds the Mojang identity. Updating only the
 * key would leave the player greeted by their old name.
 */
public final class NameReplacer {

    private final String table;
    private final String matchColumn;
    private final List<String> lowercaseColumns;
    private final List<String> exactColumns;
    private final List<String> uuidColumns;

    public NameReplacer(String table, String matchColumn, List<String> lowercaseColumns,
                        List<String> exactColumns, List<String> uuidColumns) {
        this.table = table;
        this.matchColumn = matchColumn;
        this.lowercaseColumns = new ArrayList<>(lowercaseColumns);
        this.exactColumns = new ArrayList<>(exactColumns);
        this.uuidColumns = new ArrayList<>(uuidColumns);
    }

    /**
     * @return how many rows were moved — 0 is normal (this player has nothing in this store), never an
     *         error, which is why nothing here throws on an empty result.
     */
    public int rename(Connection connection, Rename rename) throws SQLException {
        List<String> assignments = new ArrayList<>();
        for (String column : lowercaseColumns) {
            assignments.add(column + " = ?");
        }
        for (String column : exactColumns) {
            assignments.add(column + " = ?");
        }
        for (String column : uuidColumns) {
            assignments.add(column + " = ?");
        }
        if (assignments.isEmpty()) {
            return 0;
        }
        String sql = "UPDATE " + table + " SET " + String.join(", ", assignments)
                + " WHERE LOWER(" + matchColumn + ") = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            for (int i = 0; i < lowercaseColumns.size(); i++) {
                statement.setString(index++, rename.newName().toLowerCase(Locale.ROOT));
            }
            for (int i = 0; i < exactColumns.size(); i++) {
                statement.setString(index++, rename.newName());
            }
            for (int i = 0; i < uuidColumns.size(); i++) {
                statement.setString(index++, rename.uuid().toString());
            }
            statement.setString(index, rename.oldName().toLowerCase(Locale.ROOT));
            return statement.executeUpdate();
        }
    }

    /** Read-only: is this player's row where the module says it is, under the name it expects? */
    public List<Diagnosis> diagnose(Connection connection, String name, String module) {
        List<Diagnosis> report = new ArrayList<>();
        String sql = "SELECT COUNT(*) FROM " + table + " WHERE LOWER(" + matchColumn + ") = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name.toLowerCase(Locale.ROOT));
            try (ResultSet rs = statement.executeQuery()) {
                boolean found = rs.next() && rs.getInt(1) > 0;
                report.add(found
                        ? Diagnosis.verified(module, table + "." + matchColumn, 1)
                        : Diagnosis.notFound(module, table + "." + matchColumn));
            }
        } catch (SQLException e) {
            report.add(Diagnosis.error(module, table + "." + matchColumn, e.getMessage()));
        }
        return report;
    }
}
