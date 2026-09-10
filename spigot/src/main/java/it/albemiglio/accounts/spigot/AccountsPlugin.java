package it.albemiglio.accounts.spigot;

import it.albemiglio.accounts.core.modules.DiagnosisReport;
import it.albemiglio.accounts.core.modules.Module;
import it.albemiglio.accounts.core.nbt.NbtModule;
import it.albemiglio.accounts.core.objects.Task;
import it.albemiglio.accounts.core.scan.DraftWriter;
import it.albemiglio.accounts.core.scan.ScanFinding;
import it.albemiglio.accounts.core.scan.Scanner;
import it.albemiglio.accounts.core.services.AccountsEngine;
import it.albemiglio.accounts.core.services.InstanceId;
import it.albemiglio.accounts.core.services.MigrationArgs;
import it.albemiglio.accounts.core.services.ModuleService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Spigot entry point: a backend runs this so its own local databases are migrated when a UUID
 * migration is broadcast. Boots the engine (which subscribes for broadcasts and recovers anything
 * missed while the server was down) and exposes {@code /accounts migrate} for manual use.
 */
public final class AccountsPlugin extends JavaPlugin {

    private AccountsEngine engine;
    private List<Module> modules;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        FileConfiguration config = getConfig();
        Path dataDir = getDataFolder().toPath();
        Path modulesDir = dataDir.resolve(config.getString("modules-dir", "modules"));
        try {
            Files.createDirectories(modulesDir);
        } catch (IOException e) {
            getLogger().severe("Could not create the modules directory: " + e.getMessage());
            return;
        }

        ModuleService moduleService = new ModuleService(count -> { });
        moduleService.loadModules(modulesDir);
        moduleService.loadJarModules(dataDir.resolve("jar-modules"));
        moduleService.loadPluginJarModules(dataDir.toAbsolutePath().getParent());

        // When the world is being migrated on disk (NbtModule), also migrate whatever is loaded in
        // memory through the Bukkit API, so the server's next save doesn't overwrite the rewrite.
        List<Module> modules = new ArrayList<>(moduleService.getModules());
        if (modules.stream().anyMatch(module -> module instanceof NbtModule)) {
            LiveWorldModule liveWorld = new LiveWorldModule(this);
            liveWorld.enable();
            modules.add(liveWorld);
        }

        this.modules = modules;
        this.engine = AccountsEngine.start(
                config.getString("redis.host", "localhost"),
                config.getInt("redis.port", 6379),
                config.getString("redis.password", ""),
                InstanceId.loadOrCreate(dataDir),
                modules);

        getLogger().info("Accounts ready: " + modules.size() + " module(s) loaded");
    }

    @Override
    public void onDisable() {
        if (engine != null) {
            engine.close();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("accounts.migrate")) {
            sender.sendMessage("No permission.");
            return true;
        }
        if (engine == null) {
            sender.sendMessage("Accounts is not running.");
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("scan")) {
            return scan(sender, args);
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("diagnose")) {
            return diagnose(sender, args);
        }
        try {
            Task task = MigrationArgs.parse(args);
            engine.migrate(task);
            sender.sendMessage("Queued migration "
                    + task.getMigration().getLeft() + " -> " + task.getMigration().getRight());
        } catch (IllegalArgumentException e) {
            sender.sendMessage("Usage: /accounts migrate <fromUuid> <toUuid> [username]");
        }
        return true;
    }

    /**
     * {@code /accounts scan <probe-uuid>} — points the scanner at a player known to have data on this
     * server and drafts a disabled module for every place their uuid turns up, so a plugin with no
     * template and no source still gets a starting point. Read-only on plugin data; runs off the main
     * thread because it walks every plugin folder and copies each SQLite file.
     */
    private boolean scan(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sender.sendMessage("Usage: /accounts scan <probe-uuid>  (a player known to have data here)");
            return true;
        }
        final UUID probe;
        try {
            probe = UUID.fromString(args[1]);
        } catch (IllegalArgumentException e) {
            sender.sendMessage("Not a valid uuid: " + args[1]);
            return true;
        }
        Path pluginsDir = getDataFolder().toPath().toAbsolutePath().getParent();
        Path outDir = getDataFolder().toPath().resolve("scan-drafts");
        sender.sendMessage("Scanning plugin data for " + probe + " — this can take a moment…");
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            List<String> lines = new ArrayList<>();
            try {
                List<ScanFinding> findings = new Scanner().scan(pluginsDir, probe);
                if (findings.isEmpty()) {
                    lines.add("No trace of " + probe + " found — is that player's data on this server?");
                } else {
                    new DraftWriter().write(findings, outDir);
                    lines.add("Found " + findings.size() + " place(s); drafts (all disabled) written to "
                            + outDir + " — review before enabling.");
                    for (ScanFinding finding : findings) {
                        lines.add(" - " + finding.getPluginName() + ": " + finding.getNote());
                    }
                }
            } catch (IOException e) {
                lines.add("Scan failed: " + e.getMessage());
            }
            getServer().getScheduler().runTask(this, () -> lines.forEach(sender::sendMessage));
        });
        return true;
    }

    /**
     * {@code /accounts diagnose <probe-uuid>} — read-only pre-flight. For a player known to have data on
     * this server, checks every loaded module: is their uuid where the module expects it, and in the
     * assumed encoding? Surfaces FORMAT_MISMATCH / MISSING findings so an operator can confirm a migration
     * is safe (and won't silently miss data) before running it. Writes nothing; safe on a live server.
     */
    private boolean diagnose(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sender.sendMessage("Usage: /accounts diagnose <probe-uuid>  (a player known to have data here)");
            return true;
        }
        final UUID probe;
        try {
            probe = UUID.fromString(args[1]);
        } catch (IllegalArgumentException e) {
            sender.sendMessage("Not a valid uuid: " + args[1]);
            return true;
        }
        sender.sendMessage("Diagnosing " + modules.size() + " module(s) against " + probe + " (read-only)…");
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            List<String> lines = DiagnosisReport.of(modules, probe);
            getServer().getScheduler().runTask(this, () -> lines.forEach(sender::sendMessage));
        });
        return true;
    }
}
