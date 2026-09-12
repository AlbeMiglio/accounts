package it.albemiglio.accounts.core.dashboard;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The uuid a server gives a player it cannot verify: a v3 name hash, which every Bukkit and proxy
 * implementation derives the same way. It is what a cracked player's data is filed under, so it is one
 * half of the pair the panel needs — and it needs no network to work out, unlike the other half.
 */
public final class OfflineUuid {

    private OfflineUuid() {
    }

    public static UUID of(String username) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
    }
}
