package it.albemiglio.accounts.core.modules;

import it.albemiglio.accounts.core.database.DB;
import it.albemiglio.accounts.core.database.H2;
import it.albemiglio.accounts.core.database.MariaDB;
import it.albemiglio.accounts.core.database.MySQL;
import it.albemiglio.accounts.core.database.SQLite;
import it.albemiglio.accounts.core.modules.replacers.ColumnReplacer;
import it.albemiglio.accounts.core.modules.replacers.NameReplacer;
import it.albemiglio.accounts.core.modules.replacers.Replacer;
import it.albemiglio.accounts.core.modules.replacers.UuidCodec;
import it.albemiglio.accounts.core.nbt.NbtModule;
import it.albemiglio.accounts.core.objects.enums.DBType;
import it.albemiglio.accounts.core.objects.enums.Platform;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class YamlModuleFactory {

    public Module build(Map<String, Object> config) {
        String name = (String) config.get("name");
        Platform platform = Platform.valueOf(((String) config.get("platform")).toUpperCase());
        String type = (String) config.getOrDefault("type", "sql");

        Module module;
        switch (type.toLowerCase()) {
            case "file":
                module = buildFileModule(name, platform, config);
                break;
            case "world":
                module = new NbtModule(name, platform, Paths.get((String) config.get("directory")));
                break;
            case "json":
                module = new JsonModule(name, platform, Paths.get((String) config.get("directory")));
                break;
            case "content":
                module = buildContentModule(name, platform, config);
                break;
            default:
                module = buildSqlModule(name, platform, config);
        }

        if (Boolean.TRUE.equals(config.get("enabled"))) {
            module.enable();
        }
        return module;
    }

    private Module buildFileModule(String name, Platform platform, Map<String, Object> config) {
        Path directory = Paths.get((String) config.get("directory"));
        // Absent or empty extension = the bare uuid: extensionless files or whole per-player directories.
        String extension = (String) config.getOrDefault("extension", "");
        return new FileModule(name, platform, directory, extension == null ? "" : extension);
    }

    @SuppressWarnings("unchecked")
    private Module buildContentModule(String name, Platform platform, Map<String, Object> config) {
        List<ContentModule.Target> targets = new ArrayList<>();
        for (Map<String, Object> target : (List<Map<String, Object>>) config.get("targets")) {
            targets.add(new ContentModule.Target(
                    Paths.get((String) target.get("directory")),
                    (String) target.getOrDefault("pattern", "*"),
                    Boolean.TRUE.equals(target.get("recursive"))));
        }
        return new ContentModule(name, platform, targets);
    }

    @SuppressWarnings("unchecked")
    private Module buildSqlModule(String name, Platform platform, Map<String, Object> config) {
        DB database = buildDatabase((Map<String, Object>) config.get("database"));
        List<Replacer> replacers = new ArrayList<>();
        List<Map<String, Object>> replacerConfigs = (List<Map<String, Object>>) config.get("replacers");
        if (replacerConfigs != null) {
            for (Map<String, Object> replacer : replacerConfigs) {
                replacers.add(buildReplacer(replacer));
            }
        }
        List<NameReplacer> nameReplacers = new ArrayList<>();
        List<Map<String, Object>> renameConfigs = (List<Map<String, Object>>) config.get("renames");
        if (renameConfigs != null) {
            for (Map<String, Object> rename : renameConfigs) {
                nameReplacers.add(new NameReplacer(
                        (String) rename.get("table"),
                        (String) rename.getOrDefault("match", "username"),
                        (String) rename.getOrDefault("prefix", ""),
                        strings(rename.get("set-lower")),
                        strings(rename.get("set-exact")),
                        strings(rename.get("set-uuid"))));
            }
        }
        return new YamlModule(name, platform, database, replacers,
                Boolean.TRUE.equals(config.get("disable-foreign-key-checks")), nameReplacers);
    }

    @SuppressWarnings("unchecked")
    private Replacer buildReplacer(Map<String, Object> config) {
        List<ColumnReplacer.Derived> derived = new ArrayList<>();
        List<Map<String, Object>> derivedConfigs = (List<Map<String, Object>>) config.get("derived");
        if (derivedConfigs != null) {
            for (Map<String, Object> extra : derivedConfigs) {
                derived.add(new ColumnReplacer.Derived(
                        (String) extra.get("column"), (String) extra.get("fn")));
            }
        }
        return new ColumnReplacer(
                (String) config.get("table"),
                (String) config.get("table-pattern"),
                (String) config.get("column"),
                UuidCodec.of((String) config.get("format")),
                (String) config.get("prefix"),
                derived);
    }

    private DB buildDatabase(Map<String, Object> config) {
        DBType type = DBType.valueOf(((String) config.get("type")).toUpperCase());
        String host = (String) config.get("host");
        int port = config.get("port") == null ? 0 : ((Number) config.get("port")).intValue();
        String username = (String) config.get("username");
        String password = (String) config.get("password");
        String database = (String) config.get("database");

        switch (type) {
            case SQLITE:
                return new SQLite(host, port, username, password, database);
            case MYSQL:
                return new MySQL(host, port, username, password, database);
            case MARIADB:
                return new MariaDB(host, port, username, password, database);
            case H2:
                // H2 file formats are incompatible across versions, so the pin is not optional: the
                // engine fetches exactly that driver and refuses files written by any other family.
                String h2Version = (String) config.get("h2-version");
                if (h2Version == null || h2Version.trim().isEmpty()) {
                    throw new IllegalArgumentException(
                            "h2 needs 'h2-version': the exact H2 version the owning plugin bundles "
                            + "(e.g. \"2.1.214\") — file formats are incompatible across versions, so "
                            + "the engine must fetch that exact driver.");
                }
                return new H2(host, port,
                        username == null ? "sa" : username,
                        password == null ? "" : password,
                        database, h2Version,
                        (String) config.get("driver-jar"),
                        (String) config.get("driver-class"));
            default:
                throw new IllegalArgumentException("Unsupported database type: " + type);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object value) {
        return value == null ? java.util.Collections.emptyList() : (List<String>) value;
    }
}
