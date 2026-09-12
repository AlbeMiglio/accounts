package it.albemiglio.accounts.core.nbt;

import it.albemiglio.accounts.core.io.AtomicFiles;
import it.albemiglio.accounts.core.modules.Diagnosis;
import it.albemiglio.accounts.core.modules.MigrationException;
import it.albemiglio.accounts.core.modules.Module;
import it.albemiglio.accounts.core.objects.Pair;
import it.albemiglio.accounts.core.objects.enums.Platform;
import net.querz.mca.Chunk;
import net.querz.mca.LoadFlags;
import net.querz.mca.MCAFile;
import net.querz.mca.MCAUtil;
import net.querz.nbt.io.NBTUtil;
import net.querz.nbt.io.NamedTag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Migrates the vanilla world itself: every {@code .mca} region/entities/poi file is rewritten so any
 * reference to the player's old UUID (a tamed pet's {@code Owner}, a player-head's {@code SkullOwner},
 * a boss-bar viewer, a leashed mob, ...) follows them to the new UUID. Each chunk is read raw and
 * scanned whole by {@link UuidNbtRewriter}, so vanilla, modded and datapack tags are all caught and
 * nothing is lost. Files are scanned in parallel — the region scan is the bottleneck for the
 * sub-5-minute budget. The backend that owns the world migrates its own files, as the broadcast wants.
 */
public class NbtModule extends Module {

    private static final int CHUNKS_PER_REGION = 1024;

    private final Path worldDir;

    public NbtModule(String name, Platform platform, Path worldDir) {
        super(name, platform);
        this.worldDir = worldDir;
    }

    @Override
    public void execute(Pair<UUID, UUID> migration) {
        UuidNbtRewriter rewriter = new UuidNbtRewriter(migration.getLeft(), migration.getRight());
        UuidBytes probe = new UuidBytes(migration.getLeft());
        // Almost every region mentions no player at all, and deciding that on the raw bytes costs a
        // sixth of parsing it: measured on a live 1.9 GB world, 36.4 s of scanning became 6.1 s.
        regionFiles().parallelStream()
                .filter(file -> RegionPrescan.mayContain(file, probe))
                .forEach(file -> rewriteRegion(file, rewriter));
        datFiles().parallelStream().forEach(file -> rewriteDat(file, rewriter));
    }

    /**
     * The gzipped-NBT {@code .dat} files: {@code level.dat}, anything under {@code data/} (raids,
     * scoreboard, ...) and every player file under {@code playerdata/}. Scanning playerdata content
     * (not just renaming the migrant's own file) is what lets a player-head another player is holding
     * follow the migrant too.
     */
    private List<Path> datFiles() {
        List<Path> files = new ArrayList<>();
        Path level = worldDir.resolve("level.dat");
        if (Files.isRegularFile(level)) {
            files.add(level);
        }
        addDatFilesIn(worldDir.resolve("data"), files);
        addDatFilesIn(worldDir.resolve("playerdata"), files);
        return files;
    }

    private void addDatFilesIn(Path dir, List<Path> into) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> list = Files.list(dir)) {
            list.filter(p -> p.toString().endsWith(".dat")).forEach(into::add);
        } catch (IOException e) {
            throw new MigrationException("Failed to scan " + dir, e);
        }
    }

    private void rewriteDat(Path file, UuidNbtRewriter rewriter) {
        NamedTag tag;
        try {
            tag = NBTUtil.read(file.toFile());
        } catch (IOException | RuntimeException e) {
            // Not parseable as NBT (e.g. Bukkit's 16-byte uid.dat, or a corrupt file): it cannot hold a
            // migratable UUID, so skip it rather than abort — never wedge the login gate open over one file.
            return;
        }
        try {
            if (rewriter.rewrite(tag.getTag()) > 0) {
                AtomicFiles.write(file, tmp -> NBTUtil.write(tag, tmp.toFile()));
            }
        } catch (IOException e) {
            throw new MigrationException("NBT migration failed writing " + file, e);
        }
    }

    private List<Path> regionFiles() {
        if (!Files.isDirectory(worldDir)) {
            return Collections.emptyList();
        }
        try (Stream<Path> walk = Files.walk(worldDir)) {
            return walk.filter(p -> p.toString().endsWith(".mca")).collect(Collectors.toList());
        } catch (IOException e) {
            throw new MigrationException("Failed to scan world " + worldDir, e);
        }
    }

    private void rewriteRegion(Path file, UuidNbtRewriter rewriter) {
        MCAFile mca;
        try {
            mca = MCAUtil.read(file.toFile(), LoadFlags.RAW);
        } catch (IOException | RuntimeException e) {
            return; // unreadable or corrupt region: skip it rather than abort the whole world migration
        }
        int changed = 0;
        for (int i = 0; i < CHUNKS_PER_REGION; i++) {
            Chunk chunk = mca.getChunk(i);
            if (chunk != null) {
                changed += rewriter.rewrite(chunk.getHandle());
            }
        }
        if (changed == 0) {
            return;
        }
        try {
            MCAUtil.write(mca, file.toFile());
        } catch (IOException e) {
            throw new MigrationException("NBT migration failed writing " + file, e);
        }
    }

    @Override
    public List<Diagnosis> diagnose(UUID probe) {
        // World data is matched by the raw 128 bits in every known NBT encoding (int-array, dashed
        // string, long pair), so there is no assumed format that could be wrong — nothing to verify.
        return Collections.singletonList(Diagnosis.info(getName(), worldDir.toString(),
                "scan-all (every uuid encoding) — no assumed format to verify; test on a copy"));
    }
}
