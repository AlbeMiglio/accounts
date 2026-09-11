package it.albemiglio.accounts.velocity;

import com.velocitypowered.api.command.SimpleCommand;
import it.albemiglio.accounts.core.modules.DiagnosisReport;
import it.albemiglio.accounts.core.modules.Module;
import it.albemiglio.accounts.core.objects.Task;
import it.albemiglio.accounts.core.services.AccountsEngine;
import it.albemiglio.accounts.core.services.MigrationArgs;
import net.kyori.adventure.text.Component;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Admin command {@code /accounts migrate <fromUuid> <toUuid> [username]} that broadcasts a UUID
 * migration through the engine, plus the read-only {@code /accounts diagnose <probe-uuid>} pre-flight.
 * The proxy is the natural place to drive a network-wide migration, and it holds the modules that carry
 * network-wide identity (permissions, bans, skins), so it is where a pre-flight matters most.
 */
public final class MigrateCommand implements SimpleCommand {

    private static final String USAGE = "Usage: /accounts migrate <fromUuid> <toUuid> [username]"
            + " | /accounts diagnose <probe-uuid>";

    private final AccountsEngine engine;
    private final Collection<Module> modules;

    public MigrateCommand(AccountsEngine engine, Collection<Module> modules) {
        this.engine = engine;
        this.modules = modules;
    }

    @Override
    public void execute(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length > 0 && args[0].equalsIgnoreCase("diagnose")) {
            diagnose(invocation, args);
            return;
        }
        try {
            Task task = MigrationArgs.parse(invocation.arguments());
            engine.migrate(task);
            invocation.source().sendMessage(Component.text(
                    "Queued migration " + task.getMigration().getLeft() + " -> " + task.getMigration().getRight()));
        } catch (IllegalArgumentException e) {
            invocation.source().sendMessage(Component.text(USAGE));
        }
    }

    /** Read-only: probes every module for the player and reports where their data actually is. */
    private void diagnose(Invocation invocation, String[] args) {
        if (args.length != 2) {
            invocation.source().sendMessage(Component.text(
                    "Usage: /accounts diagnose <probe-uuid>  (a player known to have data here)"));
            return;
        }
        final UUID probe;
        try {
            probe = UUID.fromString(args[1]);
        } catch (IllegalArgumentException e) {
            invocation.source().sendMessage(Component.text("Not a valid uuid: " + args[1]));
            return;
        }
        invocation.source().sendMessage(Component.text(
                "Diagnosing " + modules.size() + " module(s) against " + probe + " (read-only)…"));
        // Off the calling thread: the probe queries every module's database.
        CompletableFuture.runAsync(() -> {
            List<String> lines = DiagnosisReport.of(modules, probe);
            lines.forEach(line -> invocation.source().sendMessage(Component.text(line)));
        });
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("accounts.migrate");
    }
}
