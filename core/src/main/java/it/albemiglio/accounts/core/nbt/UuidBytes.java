package it.albemiglio.accounts.core.nbt;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Whether a block of decompressed NBT could possibly contain a uuid, decided on the raw bytes without
 * building a tag tree. Parsing NBT and walking it costs four fifths of a region scan, and the
 * overwhelming majority of chunks mention no player at all — so the scan asks this first and only pays
 * for the tree where the answer is yes.
 *
 * <p>The answer is a superset of what {@link UuidNbtRewriter} can change, which is what makes skipping
 * safe. It stores a uuid three ways, and every one of them puts a recognisable run of bytes in the
 * stream: the 4-int array is {@code most||least} big-endian, so it contains {@code most}; a
 * {@code <name>Most}/{@code <name>Least} pair separates the two longs with tag headers, but the
 * {@code Most} payload is those same eight bytes; and the dashed string is its own ASCII. A chunk
 * holding none of those cannot hold the uuid in any form the rewriter recognises.
 */
public final class UuidBytes {

    private final byte[] most;
    private final byte[] dashed;

    public UuidBytes(UUID uuid) {
        this.most = eightBytes(uuid.getMostSignificantBits());
        this.dashed = uuid.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /** @param length how much of {@code data} is filled — the array is reused across chunks. */
    public boolean mayBeIn(byte[] data, int length) {
        return contains(data, length, most) || contains(data, length, dashed);
    }

    private static byte[] eightBytes(long value) {
        byte[] out = new byte[8];
        for (int i = 0; i < 8; i++) {
            out[i] = (byte) (value >>> (56 - 8 * i));
        }
        return out;
    }

    private static boolean contains(byte[] haystack, int length, byte[] needle) {
        byte first = needle[0];
        int last = length - needle.length;
        for (int i = 0; i <= last; i++) {
            if (haystack[i] != first) {
                continue;
            }
            int j = 1;
            while (j < needle.length && haystack[i + j] == needle[j]) {
                j++;
            }
            if (j == needle.length) {
                return true;
            }
        }
        return false;
    }
}
