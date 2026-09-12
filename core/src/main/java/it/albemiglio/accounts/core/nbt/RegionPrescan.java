package it.albemiglio.accounts.core.nbt;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Answers, for one region file, whether any chunk in it mentions a uuid — by inflating each chunk and
 * searching the bytes, never building an NBT tree. On a real world this decides "no" for almost every
 * file, and a "no" means the expensive path can be skipped entirely.
 *
 * <p>Reads the region format directly: a 4 KiB table of 1024 entries, each a 3-byte sector offset and a
 * 1-byte sector count, then at every offset a 4-byte length, a 1-byte compression id and the payload.
 * Anything it cannot make sense of — a truncated file, a compression it does not know, a chunk that
 * fails to inflate — is reported as "might contain it", so a damaged region is handed to the full path
 * rather than silently skipped.
 */
final class RegionPrescan {

    private static final int SECTOR = 4096;
    private static final int ENTRIES = 1024;
    private static final int COMPRESSION_ZLIB = 2;

    private RegionPrescan() {
    }

    static boolean mayContain(Path file, UuidBytes uuid) {
        byte[] region;
        try {
            region = Files.readAllBytes(file);
        } catch (IOException | OutOfMemoryError e) {
            return true; // unreadable here is the full path's problem to report, not ours to hide
        }
        if (region.length < SECTOR) {
            return true;
        }
        Inflater inflater = new Inflater();
        ByteArrayOutputStream chunk = new ByteArrayOutputStream(1 << 16);
        byte[] buffer = new byte[1 << 16];
        try {
            for (int i = 0; i < ENTRIES; i++) {
                int offset = ((region[i * 4] & 0xFF) << 16)
                        | ((region[i * 4 + 1] & 0xFF) << 8)
                        | (region[i * 4 + 2] & 0xFF);
                if (offset == 0 || (region[i * 4 + 3] & 0xFF) == 0) {
                    continue; // chunk never generated
                }
                int at = offset * SECTOR;
                if (at < 0 || at + 5 > region.length) {
                    return true;
                }
                int length = ((region[at] & 0xFF) << 24) | ((region[at + 1] & 0xFF) << 16)
                        | ((region[at + 2] & 0xFF) << 8) | (region[at + 3] & 0xFF);
                if ((region[at + 4] & 0xFF) != COMPRESSION_ZLIB || length <= 1
                        || at + 5 + length - 1 > region.length) {
                    return true; // gzip, uncompressed, external or truncated: let the full path decide
                }
                chunk.reset();
                inflater.reset();
                inflater.setInput(region, at + 5, length - 1);
                while (!inflater.finished()) {
                    int n = inflater.inflate(buffer);
                    if (n == 0) {
                        if (inflater.needsInput() || inflater.needsDictionary()) {
                            return true; // truncated stream
                        }
                        continue;
                    }
                    chunk.write(buffer, 0, n);
                }
                // Searched whole, not per buffer: a uuid straddling two inflate calls is still a uuid.
                if (uuid.mayBeIn(chunk.toByteArray(), chunk.size())) {
                    return true;
                }
            }
        } catch (DataFormatException | RuntimeException e) {
            return true;
        } finally {
            inflater.end();
        }
        return false;
    }
}
