package it.albemiglio.accounts.core.nbt;

import net.querz.mca.Chunk;
import net.querz.mca.MCAFile;
import net.querz.mca.MCAUtil;
import net.querz.nbt.tag.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The prescan decides on raw bytes whether a region can be skipped, so the only thing that matters is
 * that it never says no to a region the rewriter would have changed. One case per storage form, plus
 * the damaged-file cases, which must fall through to the full path rather than be skipped.
 */
class RegionPrescanTest {

    private static final UUID PLAYER = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private static final UUID SOMEONE_ELSE = UUID.fromString("11111111-2222-4333-8444-555555555555");

    private Path region(Path dir, String name, Consumer<CompoundTag> fill) throws IOException {
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", 2975);
        CompoundTag level = new CompoundTag();
        level.putInt("xPos", 0);
        level.putInt("zPos", 0);
        level.putString("Status", "full");
        fill.accept(level);
        root.put("Level", level);
        MCAFile mca = new MCAFile(0, 0);
        mca.setChunk(0, new Chunk(root));
        Path file = dir.resolve(name);
        MCAUtil.write(mca, file.toFile());
        return file;
    }

    @Test
    void findsTheIntArrayForm(@TempDir Path dir) throws IOException {
        Path file = region(dir, "a.mca", level -> level.putIntArray("Owner", UuidNbtRewriter.toIntArray(PLAYER)));
        assertTrue(RegionPrescan.mayContain(file, new UuidBytes(PLAYER)));
    }

    @Test
    void findsTheDashedStringForm(@TempDir Path dir) throws IOException {
        Path file = region(dir, "b.mca", level -> level.putString("OwnerUUID", PLAYER.toString()));
        assertTrue(RegionPrescan.mayContain(file, new UuidBytes(PLAYER)));
    }

    @Test
    void findsTheMostLeastLongPair(@TempDir Path dir) throws IOException {
        // The two longs are separated by tag headers in the stream, so only the Most half is contiguous
        // — which is exactly the half the probe looks for.
        Path file = region(dir, "c.mca", level -> {
            level.putLong("OwnerMost", PLAYER.getMostSignificantBits());
            level.putLong("OwnerLeast", PLAYER.getLeastSignificantBits());
        });
        assertTrue(RegionPrescan.mayContain(file, new UuidBytes(PLAYER)));
    }

    @Test
    void skipsARegionThatMentionsSomebodyElse(@TempDir Path dir) throws IOException {
        Path file = region(dir, "d.mca", level -> {
            level.putIntArray("Owner", UuidNbtRewriter.toIntArray(SOMEONE_ELSE));
            level.putString("OwnerUUID", SOMEONE_ELSE.toString());
        });
        assertFalse(RegionPrescan.mayContain(file, new UuidBytes(PLAYER)));
    }

    @Test
    void aTruncatedRegionIsHandedToTheFullPath(@TempDir Path dir) throws IOException {
        Path file = region(dir, "e.mca", level -> level.putIntArray("Owner", UuidNbtRewriter.toIntArray(PLAYER)));
        byte[] all = Files.readAllBytes(file);
        Files.write(file, java.util.Arrays.copyOf(all, 5000));
        assertTrue(RegionPrescan.mayContain(file, new UuidBytes(PLAYER)));
    }

    @Test
    void aMissingFileIsHandedToTheFullPath(@TempDir Path dir) {
        assertTrue(RegionPrescan.mayContain(dir.resolve("nope.mca"), new UuidBytes(PLAYER)));
    }
}
