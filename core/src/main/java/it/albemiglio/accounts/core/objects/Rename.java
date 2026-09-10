package it.albemiglio.accounts.core.objects;

import java.util.Objects;
import java.util.UUID;

/**
 * The other half of an identity change: the name moves and the uuid stays. A premium account keeps its
 * Mojang uuid across a rename, so this is what has to reach every store still keyed by the name — the
 * mirror image of a {@link Task}, where the uuid moves and the name stays.
 *
 * <p>Carries the uuid as well as the two names: a store keyed by name may also hold the identity in a
 * column, and a rename is the moment to write it.
 */
public final class Rename {

    private static final String PREFIX = "rename";

    private final UUID uuid;
    private final String oldName;
    private final String newName;

    public Rename(UUID uuid, String oldName, String newName) {
        this.uuid = Objects.requireNonNull(uuid, "uuid");
        this.oldName = Objects.requireNonNull(oldName, "oldName");
        this.newName = Objects.requireNonNull(newName, "newName");
    }

    public UUID uuid() {
        return uuid;
    }

    public String oldName() {
        return oldName;
    }

    public String newName() {
        return newName;
    }

    /** Stable and idempotent: the same rename applied twice is the same id, so it is only applied once. */
    public String id() {
        return PREFIX + ":" + uuid + ":" + newName.toLowerCase(java.util.Locale.ROOT);
    }

    public static boolean looksLikeOne(String wire) {
        return wire != null && wire.startsWith(PREFIX + ";");
    }

    @Override
    public String toString() {
        return PREFIX + ";" + uuid + ";" + oldName + ";" + newName;
    }

    /** Minecraft names cannot contain ';', so the split is unambiguous. */
    public static Rename fromString(String wire) {
        String[] parts = wire.split(";", -1);
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            throw new IllegalArgumentException("Malformed rename: " + wire);
        }
        return new Rename(UUID.fromString(parts[1]), parts[2], parts[3]);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Rename)) return false;
        Rename other = (Rename) o;
        return uuid.equals(other.uuid) && oldName.equals(other.oldName) && newName.equals(other.newName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uuid, oldName, newName);
    }
}
