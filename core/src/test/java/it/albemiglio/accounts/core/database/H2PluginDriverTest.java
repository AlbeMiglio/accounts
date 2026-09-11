package it.albemiglio.accounts.core.database;

import it.albemiglio.accounts.core.modules.MigrationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A plugin that shades H2 writes its relocated class names into the store's type metadata, so only its
 * own jar can open the file — GravesX's graves.data reads back as "File corrupted" through any stock
 * driver. Such a jar is written by the plugin at runtime under a name carrying a build hash, hence the
 * trailing wildcard, and it is the server's own file rather than something we fetched, hence no checksum.
 */
class H2PluginDriverTest {

    /** The H2 on the test classpath, standing in for the jar a plugin would have written. */
    private static Path testClasspathH2() throws Exception {
        return Paths.get(Class.forName("org.h2.Driver").getProtectionDomain()
                .getCodeSource().getLocation().toURI());
    }

    @Test
    void usesThePluginsOwnJarAndPrefersTheNewestMatch(@TempDir Path dir) throws Exception {
        String base = dir.resolve("graves.data").toString();
        try (Connection c = DriverManager.getConnection("jdbc:h2:file:" + base, "sa", "");
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE grave (owner_uuid VARCHAR(36))");
            st.execute("INSERT INTO grave VALUES ('069a79f4-44e9-4726-a5be-fca90e38aaf5')");
        }
        Path lib = Files.createDirectories(dir.resolve("lib"));
        // The stale one is not a jar at all: picking it instead of the newest would fail loudly.
        Files.write(lib.resolve("h2.jar-relocated-1111"), "not a jar".getBytes());
        Files.copy(testClasspathH2(), lib.resolve("h2.jar-relocated-2222"));
        lib.resolve("h2.jar-relocated-1111").toFile().setLastModified(1_000_000L);

        H2 h2 = new H2("", 0, "sa", "", base, "2.4.240",
                lib.resolve("h2.jar-relocated-*").toString(), "org.h2.Driver");

        try (Connection c = h2.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM grave")) {
            rs.next();
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void urlOptionsReachTheDatabase(@TempDir Path dir) throws Exception {
        // MobPuppets writes its tables into a lower-case "public" schema; without the setting it was
        // created under, replaying that DDL fails with Schema "public" not found.
        String base = dir.resolve("puppets").toString();
        try (Connection c = DriverManager.getConnection(
                "jdbc:h2:file:" + base + ";DATABASE_TO_LOWER=TRUE", "sa", "");
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE \"public\".\"pp_pedine\" (\"owner_uuid\" VARCHAR(36))");
        }
        H2 h2 = new H2("", 0, "sa", "", base, "2.4.240", "", "", "DATABASE_TO_LOWER=TRUE");

        try (Connection c = h2.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM \"public\".\"pp_pedine\"")) {
            rs.next();
            assertEquals(0, rs.getInt(1));
        }
    }

    @Test
    void aMissingPluginJarSaysWhoWritesIt(@TempDir Path dir) {
        H2 h2 = new H2("", 0, "sa", "", dir.resolve("db").toString(), "2.4.240",
                dir.resolve("lib").resolve("h2.jar-relocated-*").toString(), "org.h2.Driver");
        MigrationException e = assertThrows(MigrationException.class, h2::getConnection);
        assertTrue(e.getMessage().contains("mv.db") || e.getMessage().contains("first start"), e.getMessage());
    }
}
