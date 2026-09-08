package it.albemiglio.accounts.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import it.albemiglio.accounts.api.MigrationService;
import it.albemiglio.accounts.api.MigrationStatus;
import it.albemiglio.accounts.core.dashboard.MigrationDashboard;
import it.albemiglio.accounts.core.services.AccountsEngine;
import it.albemiglio.accounts.core.services.InstanceId;
import it.albemiglio.accounts.core.services.ModuleService;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.UUID;

/**
 * Velocity entry point: boots the migration engine and exposes {@code /accounts migrate}. The proxy
 * is where a network-wide UUID migration is driven from, with the per-database work done by the
 * modules configured in the data folder.
 */
@Plugin(id = "accounts", name = "Accounts", version = "1.0-SNAPSHOT", authors = {"albemiglio"})
public class AccountsPlugin implements MigrationService {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private AccountsEngine engine;
    private MigrationDashboard dashboard;

    @Inject
    public AccountsPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        try {
            Map<String, Object> config = loadConfig();
            Map<String, Object> redisConfig = section(config, "redis");

            ModuleService moduleService = new ModuleService(count -> { });
            Path modulesDir = dataDirectory.resolve((String) config.getOrDefault("modules-dir", "modules"));
            Files.createDirectories(modulesDir);
            moduleService.loadModules(modulesDir);
            moduleService.loadJarModules(dataDirectory.resolve("jar-modules"));
            moduleService.loadPluginJarModules(dataDirectory.toAbsolutePath().getParent());

            this.engine = AccountsEngine.start(
                    (String) redisConfig.getOrDefault("host", "localhost"),
                    ((Number) redisConfig.getOrDefault("port", 6379)).intValue(),
                    (String) redisConfig.getOrDefault("password", ""),
                    InstanceId.loadOrCreate(dataDirectory),
                    moduleService.getModules());

            Map<String, Object> dashboardConfig = section(config, "dashboard");
            if (Boolean.TRUE.equals(dashboardConfig.get("enabled"))) {
                String bind = (String) dashboardConfig.getOrDefault("bind", "127.0.0.1");
                int dashboardPort = ((Number) dashboardConfig.getOrDefault("port", 8081)).intValue();
                this.dashboard = MigrationDashboard.start(bind, dashboardPort,
                        (String) dashboardConfig.getOrDefault("token", ""), engine::inFlight);
                logger.info("Migration dashboard on http://{}:{} (token required)", bind, this.dashboard.port());
            }

            CommandManager commands = proxy.getCommandManager();
            CommandMeta meta = commands.metaBuilder("accounts").build();
            commands.register(meta, new MigrateCommand(engine));

            logger.info("Accounts ready: {} module(s) loaded", moduleService.getModules().size());
        } catch (Exception e) {
            logger.error("Accounts failed to start", e);
        }
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (dashboard != null) {
            dashboard.close();
        }
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

    private Map<String, Object> loadConfig() throws IOException {
        Files.createDirectories(dataDirectory);
        Path configFile = dataDirectory.resolve("config.yml");
        if (!Files.exists(configFile)) {
            try (InputStream defaults = getClass().getResourceAsStream("/config.yml")) {
                if (defaults != null) {
                    Files.copy(defaults, configFile);
                }
            }
        }
        try (InputStream in = Files.newInputStream(configFile)) {
            Map<String, Object> loaded = new Yaml().load(in);
            return loaded != null ? loaded : Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> config, String key) {
        Object value = config.get(key);
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }
}
