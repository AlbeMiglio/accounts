package it.albemiglio.accounts.core.dashboard;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * Asks Mojang which identity a name belongs to. Only the premium half of a transfer needs this — the
 * offline half is a hash of the name — and an operator typing a name into the panel should not also
 * have to go and look the uuid up by hand.
 */
public final class MojangNames {

    private static final String ENDPOINT = "https://api.mojang.com/users/profiles/minecraft/";
    private static final int TIMEOUT_MS = 5000;

    private MojangNames() {
    }

    /** Empty when the name is not a premium account, or when Mojang cannot be reached right now. */
    public static Optional<UUID> premiumUuid(String username) {
        if (username == null || !username.matches("[A-Za-z0-9_]{1,16}")) {
            return Optional.empty();
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(ENDPOINT + username).openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/json");
            if (connection.getResponseCode() != 200) {
                return Optional.empty();   // 204/404: no such premium account
            }
            try (InputStreamReader reader = new InputStreamReader(connection.getInputStream(),
                    StandardCharsets.UTF_8)) {
                JsonObject body = JsonParser.parseReader(reader).getAsJsonObject();
                return Optional.of(dashed(body.get("id").getAsString()));
            }
        } catch (Exception e) {
            return Optional.empty();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** Mojang answers with the 32 hex digits and no dashes; every store here speaks the dashed form. */
    static UUID dashed(String undashed) {
        return UUID.fromString(undashed.replaceFirst(
                "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})",
                "$1-$2-$3-$4-$5"));
    }
}
