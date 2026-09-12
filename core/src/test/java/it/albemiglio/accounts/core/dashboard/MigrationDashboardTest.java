package it.albemiglio.accounts.core.dashboard;

import it.albemiglio.accounts.api.MigrationStatus;
import it.albemiglio.accounts.core.services.RedisMigrationTimings;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dashboard is the only network surface accounts opens, so the tests are about who gets in: it must
 * refuse to exist without a token, refuse requests that don't carry the right one, and never leak the
 * migration list to an unauthorised caller.
 */
class MigrationDashboardTest {

    private static final UUID OLD = new UUID(0L, 1L);
    private static final UUID NEW = new UUID(0L, 2L);

    private static List<MigrationStatus> oneStuck() {
        return List.of(new MigrationStatus(OLD, NEW, "Notch", Set.of("proxy", "hub-2"), Set.of("proxy")));
    }

    @Test
    void refusesToStartWithoutAToken() {
        assertThrows(IllegalArgumentException.class,
                () -> MigrationDashboard.start("127.0.0.1", 0, "  ", List::of));
    }

    @Test
    void servesTheMigrationsToAHolderOfTheToken() throws IOException {
        try (MigrationDashboard dashboard = MigrationDashboard.start("127.0.0.1", 0, "s3cret", MigrationDashboardTest::oneStuck)) {
            Response response = get(dashboard.port(), "/api/migrations?token=s3cret", null);

            assertEquals(200, response.status);
            assertTrue(response.body.contains("\"username\":\"Notch\""), response.body);
            assertTrue(response.body.contains("\"waitingOn\":[\"hub-2\"]"), response.body);
            assertTrue(response.body.contains("\"appliedCount\":1"), response.body);
        }
    }

    @Test
    void acceptsTheTokenAsABearerHeaderToo() throws IOException {
        try (MigrationDashboard dashboard = MigrationDashboard.start("127.0.0.1", 0, "s3cret", MigrationDashboardTest::oneStuck)) {
            assertEquals(200, get(dashboard.port(), "/api/migrations", "Bearer s3cret").status);
        }
    }

    @Test
    void turnsAwayEveryRequestWithoutTheRightToken() throws IOException {
        try (MigrationDashboard dashboard = MigrationDashboard.start("127.0.0.1", 0, "s3cret", MigrationDashboardTest::oneStuck)) {
            for (String path : List.of("/api/migrations", "/api/migrations?token=", "/api/migrations?token=wrong", "/")) {
                Response response = get(dashboard.port(), path, null);

                assertEquals(401, response.status, path);
                assertFalse(response.body.contains("Notch"), "leaked migration data on " + path);
            }
        }
    }

    @Test
    void servesThePageOnlyToAnAuthorisedCaller() throws IOException {
        try (MigrationDashboard dashboard = MigrationDashboard.start("127.0.0.1", 0, "s3cret", MigrationDashboardTest::oneStuck)) {
            Response response = get(dashboard.port(), "/?token=s3cret", null);

            assertEquals(200, response.status);
            // Anchored on the page's identity, not its wording: the copy is free to change.
            assertTrue(response.body.contains("<title>accounts</title>"), response.body);
        }
    }

    @Test
    void aMintedLinkOpensThePanelAndTheStandingSecretStaysOutOfIt() throws IOException {
        try (MigrationDashboard dashboard = MigrationDashboard.start("127.0.0.1", 0, "s3cret",
                MigrationDashboardTest::oneStuck)) {
            String link = dashboard.mintLink(30);

            assertNotEquals("s3cret", link);
            assertEquals(200, get(dashboard.port(), "/?token=" + link, null).status);
            assertEquals(401, get(dashboard.port(), "/?token=" + link + "x", null).status);
        }
    }

    @Test
    void anExpiredLinkStopsWorking() throws IOException {
        try (MigrationDashboard dashboard = MigrationDashboard.start("127.0.0.1", 0, "s3cret",
                MigrationDashboardTest::oneStuck)) {
            String link = dashboard.mintLink(0);   // already past its window

            assertEquals(401, get(dashboard.port(), "/?token=" + link, null).status);
        }
    }

    @Test
    void reportsTheAveragePerModuleAndPerInstance() {
        RedisMigrationTimings.Snapshot snapshot = new RedisMigrationTimings.Snapshot();
        snapshot.modules.put("world-eris", fields("runs", "4", "millis", "40000", "max", "29000", "failures", "1"));
        snapshot.instances.put("kingdoms", fields("runs", "2", "millis", "60000", "max", "35000"));
        snapshot.recent.add("a>b\tkingdoms\t35000\t84\t1757700000000");

        String json = new String(MigrationDashboard.analytics(snapshot), StandardCharsets.UTF_8);

        assertTrue(json.contains("\"name\":\"world-eris\""), json);
        assertTrue(json.contains("\"average\":10000"), json);   // 40000 / 4 runs
        assertTrue(json.contains("\"failures\":1"), json);
        assertTrue(json.contains("\"average\":30000"), json);   // 60000 / 2 runs
        assertTrue(json.contains("\"modules\":84"), json);
    }

    /** A module with no run recorded is not an average of zero — it is left out entirely. */
    @Test
    void aModuleThatNeverRanIsNotReported() {
        RedisMigrationTimings.Snapshot snapshot = new RedisMigrationTimings.Snapshot();
        snapshot.modules.put("never", fields("runs", "0", "millis", "0"));

        assertTrue(new String(MigrationDashboard.analytics(snapshot), StandardCharsets.UTF_8)
                .contains("\"modules\":[]"));
    }

    private static java.util.Map<String, String> fields(String... pairs) {
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static final class Response {
        final int status;
        final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private static Response get(int port, String path, String authorization) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        connection.setRequestMethod("GET");
        if (authorization != null) {
            connection.setRequestProperty("Authorization", authorization);
        }
        int status = connection.getResponseCode();
        InputStream stream = status < 400 ? connection.getInputStream() : connection.getErrorStream();
        String body = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        return new Response(status, body);
    }
}