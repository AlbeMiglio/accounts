package it.albemiglio.accounts.core.modules;

import it.albemiglio.accounts.core.modules.replacers.NameReplacer;
import it.albemiglio.accounts.core.objects.Rename;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Against a real database, on the shape that forced this to exist: AuthMe's table, where the key is the
 * lower-cased name, the display form is a second column, and the identity is a third.
 */
class NameReplacerTest {

    private static final UUID ID = UUID.fromString("485957ec-732a-4545-87ac-fc9ccf086cf2");
    private Connection connection;

    @BeforeEach
    void setUp() throws SQLException {
        connection = DriverManager.getConnection("jdbc:h2:mem:rename;DB_CLOSE_DELAY=-1");
        try (Statement s = connection.createStatement()) {
            s.execute("DROP TABLE IF EXISTS authme");
            s.execute("CREATE TABLE authme (username VARCHAR(255) PRIMARY KEY, realname VARCHAR(255), "
                    + "password VARCHAR(255), premiumUUID VARCHAR(36))");
            s.execute("INSERT INTO authme VALUES ('oldname', 'OldName', 'hash', NULL)");
            s.execute("INSERT INTO authme VALUES ('someoneelse', 'SomeoneElse', 'other', NULL)");
        }
    }

    private static NameReplacer authme() {
        return new NameReplacer("authme", "username",
                List.of("username"), List.of("realname"), List.of("premiumUUID"));
    }

    private String[] row(String key) throws SQLException {
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("SELECT realname, password, premiumUUID FROM authme "
                     + "WHERE username = '" + key + "'")) {
            return rs.next() ? new String[]{rs.getString(1), rs.getString(2), rs.getString(3)} : null;
        }
    }

    @Test
    void movesTheKeyTheDisplayNameAndTheIdentityInOneGo() throws SQLException {
        assertEquals(1, authme().rename(connection, new Rename(ID, "OldName", "NewName")));

        assertNull(row("oldname"), "the row is not left behind under the old key");
        String[] moved = row("newname");
        assertEquals("NewName", moved[0], "the display name follows, or the player is greeted by the old one");
        assertEquals("hash", moved[1], "the password is untouched — this is a rename, not a reset");
        assertEquals(ID.toString(), moved[2], "and the identity column is filled in while we are here");
    }

    @Test
    void leavesEveryoneElseAlone() throws SQLException {
        authme().rename(connection, new Rename(ID, "OldName", "NewName"));

        String[] other = row("someoneelse");
        assertEquals("SomeoneElse", other[0]);
        assertNull(other[2]);
    }

    @Test
    void aPlayerWithNoRowIsNotAnError() throws SQLException {
        assertEquals(0, authme().rename(connection, new Rename(ID, "NeverPlayedHere", "Whatever")));
    }

    @Test
    void appliedTwiceIsAppliedOnce() throws SQLException {
        Rename rename = new Rename(ID, "OldName", "NewName");

        assertEquals(1, authme().rename(connection, rename));
        assertEquals(0, authme().rename(connection, rename), "the second pass finds nothing to move");
        assertEquals("NewName", row("newname")[0]);
    }
}
