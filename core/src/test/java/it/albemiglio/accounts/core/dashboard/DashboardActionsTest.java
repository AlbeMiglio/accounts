package it.albemiglio.accounts.core.dashboard;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The acting half of the panel. Everything else it serves is a read, which is what makes a leaked
 * token survivable; these endpoints move player data, so what they refuse matters as much as what
 * they do.
 */
class DashboardActionsTest {

    private static final UUID FROM = UUID.fromString("96b3af3b-e480-3879-88e4-6858f4535713");
    private static final UUID TO = UUID.fromString("cc0685ca-7daf-40a5-8c18-a2e997cad2bb");

    /** Records what it was asked to do instead of doing it. */
    private static final class Recorder implements DashboardActions {
        final List<String> done = new ArrayList<>();

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public void migrate(UUID from, UUID to, String username) {
            done.add("migrate " + from + " " + to + " " + username);
        }

        @Override
        public void rename(UUID uuid, String oldName, String newName) {
            done.add("rename " + uuid + " " + oldName + " " + newName);
        }

        @Override
        public Optional<UUID> premiumUuid(String username) {
            return "Salefre7889".equals(username) ? Optional.of(TO) : Optional.empty();
        }

        @Override
        public List<it.albemiglio.accounts.core.services.RedisMigrationStore.Transfer> history(String who) {
            return Collections.emptyList();
        }

        @Override
        public java.util.Set<String> activeInstances() {
            return Collections.emptySet();
        }

        @Override
        public java.util.Map<String, String> progress(String migrationId) {
            return Collections.singletonMap("kingdoms", "61/85@1757700000000");
        }
    }

    private MigrationDashboard panel(DashboardActions actions) throws IOException {
        return MigrationDashboard.start("127.0.0.1", 0, "s3cret", Collections::emptyList,
                it.albemiglio.accounts.core.services.RedisMigrationTimings.Snapshot::new, actions);
    }

    @Test
    void movesAnAccountWhenActionsAreOn() throws IOException {
        Recorder actions = new Recorder();
        try (MigrationDashboard panel = panel(actions)) {
            Response r = post(panel.port(), "/api/actions/migrate?token=s3cret",
                    "from=" + FROM + "&to=" + TO + "&username=Salefre7889");

            assertEquals(200, r.status);
            assertEquals(Collections.singletonList("migrate " + FROM + " " + TO + " Salefre7889"), actions.done);
        }
    }

    @Test
    void refusesEverythingWhenActionsAreOff() throws IOException {
        try (MigrationDashboard panel = panel(DashboardActions.NONE)) {
            Response r = post(panel.port(), "/api/actions/migrate?token=s3cret", "from=" + FROM + "&to=" + TO);

            assertEquals(403, r.status);
            assertTrue(r.body.contains("read-only"), r.body);
        }
    }

    @Test
    void refusesAnUnauthorisedCallerBeforeItLooksAtTheBody() throws IOException {
        Recorder actions = new Recorder();
        try (MigrationDashboard panel = panel(actions)) {
            assertEquals(401, post(panel.port(), "/api/actions/migrate?token=wrong",
                    "from=" + FROM + "&to=" + TO).status);
            assertTrue(actions.done.isEmpty());
        }
    }

    @Test
    void refusesAMoveOntoTheSameIdentity() throws IOException {
        Recorder actions = new Recorder();
        try (MigrationDashboard panel = panel(actions)) {
            Response r = post(panel.port(), "/api/actions/migrate?token=s3cret", "from=" + TO + "&to=" + TO);

            assertEquals(400, r.status);
            assertTrue(actions.done.isEmpty());
        }
    }

    @Test
    void refusesARenameWithoutBothNames() throws IOException {
        Recorder actions = new Recorder();
        try (MigrationDashboard panel = panel(actions)) {
            assertEquals(400, post(panel.port(), "/api/actions/rename?token=s3cret",
                    "uuid=" + TO + "&old=Sale&new=").status);
            assertTrue(actions.done.isEmpty());
        }
    }

    @Test
    void findsBothHalvesOfAPlayerFromTheirNameAlone() {
        String json = new String(MigrationDashboard.player("Salefre7889", new Recorder()),
                StandardCharsets.UTF_8);

        assertTrue(json.contains("\"offline\":\"" + OfflineUuid.of("Salefre7889") + "\""), json);
        assertTrue(json.contains("\"premium\":\"" + TO + "\""), json);
    }

    /** A name Mojang does not know has no premium half — the panel must not offer to move them. */
    @Test
    void leavesThePremiumHalfOutWhenMojangHasNoSuchName() {
        String json = new String(MigrationDashboard.player("NotAPlayer", new Recorder()),
                StandardCharsets.UTF_8);

        assertTrue(json.contains("\"offline\""), json);
        assertTrue(!json.contains("\"premium\""), json);
    }

    /** A uuid pasted straight in is looked up as itself — no name, no Mojang, no offline hash. */
    @Test
    void aPastedUuidIsLookedUpAsItself() {
        String json = new String(MigrationDashboard.player(TO.toString(), new Recorder()),
                StandardCharsets.UTF_8);

        assertTrue(json.contains("\"uuid\":\"" + TO + "\""), json);
        assertTrue(!json.contains("\"offline\""), json);
    }

    @Test
    void mojangsUndashedFormBecomesTheDashedOneEveryStoreUses() {
        assertEquals(UUID.fromString("cc0685ca-7daf-40a5-8c18-a2e997cad2bb"),
                MojangNames.dashed("cc0685ca7daf40a58c18a2e997cad2bb"));
    }

    /**
     * Applied-or-not says nothing about whether a server is moving, so the in-flight feed carries how
     * far through its own modules each one is, and since when: the modules are nothing like equal in
     * cost, so a count alone cannot tell a server that is working from one that is dragging.
     */
    @Test
    void theInFlightFeedCarriesHowFarEachServerHasGot() {
        it.albemiglio.accounts.api.MigrationStatus status = new it.albemiglio.accounts.api.MigrationStatus(
                FROM, TO, "Salefre7889",
                new java.util.LinkedHashSet<>(java.util.Arrays.asList("velocity", "kingdoms")),
                java.util.Collections.singleton("velocity"));

        String json = new String(MigrationDashboard.json(Collections.singletonList(status), new Recorder()),
                StandardCharsets.UTF_8);

        assertTrue(json.contains("\"progress\":{\"kingdoms\":\"61/85@1757700000000\"}"), json);
    }

    private static final class Response {
        final int status;
        final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private static Response post(int port, String path, String body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path)
                .openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int status = connection.getResponseCode();
        java.io.InputStream stream = status < 400 ? connection.getInputStream() : connection.getErrorStream();
        String text = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        connection.disconnect();
        return new Response(status, text);
    }
}
