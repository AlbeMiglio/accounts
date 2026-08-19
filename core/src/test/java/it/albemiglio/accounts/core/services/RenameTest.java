package it.albemiglio.accounts.core.services;

import it.albemiglio.accounts.core.modules.Module;
import it.albemiglio.accounts.core.objects.Rename;
import it.albemiglio.accounts.core.objects.enums.Platform;
import it.albemiglio.accounts.core.objects.Pair;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A rename is the mirror of a migration: the name moves, the identity stays. The tests are about the
 * two ways it can go wrong — being applied twice to a store that is not idempotent, and being lost by
 * an instance that was down when it happened.
 */
class RenameTest {

    private static final UUID ID = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    static class RecordingModule extends Module {
        final List<Rename> renames = new ArrayList<>();

        RecordingModule() {
            super("rec", Platform.SPIGOT, null);
            enable();
        }

        @Override
        public void execute(Pair<UUID, UUID> migration) {
        }

        @Override
        public void rename(Rename rename) {
            renames.add(rename);
        }
    }

    @Test
    void survivesTheWireUnchanged() {
        Rename original = new Rename(ID, "OldName", "NewName");

        Rename back = Rename.fromString(original.toString());

        assertEquals(original, back);
        assertTrue(Rename.looksLikeOne(original.toString()));
        assertEquals("rename:" + ID + ":newname", original.id(), "the id is case-insensitive on the name");
    }

    @Test
    void aMigrationIsNotMistakenForARename() {
        assertTrue(!Rename.looksLikeOne(ID + ";" + ID + ";Notch;0"));
        assertThrows(IllegalArgumentException.class, () -> Rename.fromString("rename;not-a-uuid;a"));
    }

    @Test
    void appliedOnceEvenIfItArrivesTwice() {
        // The broadcast and the catch-up overlap by design, so a module must not see the same rename
        // twice: a store that appends rather than replaces would double the row.
        BroadcastMigrationServiceTest.FakeStore store = new BroadcastMigrationServiceTest.FakeStore();
        RecordingModule module = new RecordingModule();
        InstanceMigrator migrator = new InstanceMigrator("inst-1", List.of(module), store);
        Rename rename = new Rename(ID, "OldName", "NewName");

        migrator.apply(rename);
        migrator.apply(rename);

        assertEquals(1, module.renames.size());
    }

    @Test
    void anInstanceThatWasDownCatchesUpOnStartup() {
        BroadcastMigrationServiceTest.FakeStore store = new BroadcastMigrationServiceTest.FakeStore();
        Rename rename = new Rename(ID, "OldName", "NewName");
        store.record(rename);            // happened while this instance was off
        RecordingModule module = new RecordingModule();
        BroadcastMigrationService service = new BroadcastMigrationService("inst-1",
                new InstanceMigrator("inst-1", List.of(module), store), store,
                new BroadcastMigrationServiceTest.RecordingPublisher(),
                new BroadcastMigrationServiceTest.FakeRegistry());

        service.recoverPending();

        assertEquals(List.of(rename), module.renames);
    }

    @Test
    void aReceivedRenameIsRecordedSoTheNextInstanceCanCatchUp() {
        BroadcastMigrationServiceTest.FakeStore store = new BroadcastMigrationServiceTest.FakeStore();
        RecordingModule module = new RecordingModule();
        BroadcastMigrationService service = new BroadcastMigrationService("inst-1",
                new InstanceMigrator("inst-1", List.of(module), store), store,
                new BroadcastMigrationServiceTest.RecordingPublisher(),
                new BroadcastMigrationServiceTest.FakeRegistry());

        service.handle(new Rename(ID, "OldName", "NewName"));

        assertEquals(1, module.renames.size());
        assertEquals(1, store.pendingRenames("someone-else").size(),
                "durability does not depend on who published it");
    }
}
