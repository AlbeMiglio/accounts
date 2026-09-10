package it.albemiglio.accounts.bungee;

import it.albemiglio.accounts.api.MigrationService;
import it.albemiglio.accounts.api.MigrationStatus;
import it.albemiglio.accounts.core.services.AccountsEngine;
import it.albemiglio.accounts.core.services.InstanceId;
import it.albemiglio.accounts.core.services.ModuleService;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * BungeeCord entry point. Same broadcast engine as the other platforms; a network running Bungee
 * proxies (one or several) gets the same migration fan-out and restart catch-up as Velocity.
 */
public final class AccountsPlugin extends Plugin implements MigrationService {

    private AccountsEngine engine;

    @Override
    public void onEnable() {
        try {
            Configuration config = loadConfig();
            Path dataDir = getDataFolder().toPath();
            Path modulesDir = dataDir.resolve(config.getString("modules-dir", "modules"));
            Files.createDirectories(modulesDir);

            ModuleService moduleService = new ModuleService(count -> { });
            moduleService.loadModules(modulesDir);
            moduleService.loadJarModules(dataDir.resolve("jar-modules"));
            moduleService.loadPluginJarModules(dataDir.toAbsolutePath().getParent());

            this.engine = AccountsEngine.start(
                    config.getString("redis.host", "localhost"),
                    config.getInt("redis.port", 6379),
                    config.getString("redis.password", ""),
                    InstanceId.loadOrCreate(dataDir),
                    moduleService.getModules());

            getProxy().getPluginManager().registerCommand(this, new MigrateCommand(engine));
            getLogger().info("Accounts ready: " + moduleService.getModules().size() + " module(s) loaded");
        } catch (Exception e) {
            getLogger().severe("Accounts failed to start: " + e.getMessage());
        }
    }

    @Override
    public void onDisable() {
        if (engine != null) {
            engine.close();
        }
    }

    @Override
    public void migrate(UUID from, UUID to, String username) {
        if (engine != null) {
            engine.migrate(from, to, username);
        }
    }

    @Override
    public void rename(UUID uuid, String oldName, String newName) {
        if (engine != null) {
            engine.rename(uuid, oldName, newName);
        }
    }

    @Override
    public boolean isMigrationInProgress(UUID from, UUID to) {
        return engine != null && engine.isInProgress(from, to);
    }

    @Override
    public MigrationStatus migrationStatus(UUID from, UUID to) {
        return engine == null
                ? new MigrationStatus(from, to, "", java.util.Collections.emptySet(), java.util.Collections.emptySet())
                : engine.status(from, to);
    }

    @Override
    public List<MigrationStatus> migrationsInFlight() {
        return engine == null ? java.util.Collections.emptyList() : engine.inFlight();
    }

    private Configuration loadConfig() throws IOException {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        File file = new File(getDataFolder(), "config.yml");
        if (!file.exists()) {
            try (InputStream in = getResourceAsStream("config.yml")) {
                Files.copy(in, file.toPath());
            }
        }
        return ConfigurationProvider.getProvider(YamlConfiguration.class).load(file);
    }
}
