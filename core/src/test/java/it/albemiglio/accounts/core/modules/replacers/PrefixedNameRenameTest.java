package it.albemiglio.accounts.core.modules.replacers;

import it.albemiglio.accounts.core.objects.Rename;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * TicketManager files an assignee as {@code PLAYER.<username>} in a column that also holds {@code
 * CONSOLE}, {@code NOBODY} and {@code GROUP.<name>} — so the prefix belongs to the stored value, and a
 * rename has to match and write it. Without that, the ticket stays assigned to a name nobody has.
 */
class PrefixedNameRenameTest {

    private static final UUID PLAYER = UUID.randomUUID();

    private static Connection tickets() throws Exception {
        Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE tickets (id INT, assigned_to TEXT)");
            st.execute("INSERT INTO tickets VALUES (1, 'PLAYER.Salefre7889')");
            st.execute("INSERT INTO tickets VALUES (2, 'CONSOLE')");
            st.execute("INSERT INTO tickets VALUES (3, 'GROUP.Salefre7889')");
        }
        return conn;
    }

    private static String assignee(Connection conn, int id) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT assigned_to FROM tickets WHERE id = " + id)) {
            rs.next();
            return rs.getString(1);
        }
    }

    @Test
    void carriesThePrefixThroughTheRename() throws Exception {
        try (Connection conn = tickets()) {
            NameReplacer replacer = new NameReplacer("tickets", "assigned_to", "PLAYER.",
                    Collections.emptyList(), Collections.singletonList("assigned_to"),
                    Collections.emptyList());

            int moved = replacer.rename(conn, new Rename(PLAYER, "Salefre7889", "Salefre"));

            assertEquals(1, moved);
            assertEquals("PLAYER.Salefre", assignee(conn, 1));
            assertEquals("CONSOLE", assignee(conn, 2));
            // Same name, different kind of assignee: a bare LOWER(name) match would have taken it too.
            assertEquals("GROUP.Salefre7889", assignee(conn, 3));
        }
    }

    @Test
    void findsThePrefixedRowInTheDiagnosis() throws Exception {
        try (Connection conn = tickets()) {
            List<it.albemiglio.accounts.core.modules.Diagnosis> report =
                    new NameReplacer("tickets", "assigned_to", "PLAYER.", Collections.emptyList(),
                            Collections.singletonList("assigned_to"), Collections.emptyList())
                            .diagnose(conn, "Salefre7889", "ticketmanager");

            assertEquals(it.albemiglio.accounts.core.modules.Diagnosis.Status.VERIFIED,
                    report.get(0).getStatus());
        }
    }
}
