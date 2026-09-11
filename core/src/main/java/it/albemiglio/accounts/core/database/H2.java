package it.albemiglio.accounts.core.database;

import it.albemiglio.accounts.core.io.AtomicFiles;
import it.albemiglio.accounts.core.modules.MigrationException;
import it.albemiglio.accounts.core.objects.enums.DBType;

import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * H2 is special: its file formats are mutually incompatible across versions (1.4.x writes MVStore
 * format 1 or the legacy PageStore; 2.0/2.1 speak only format 2; 2.2+ only format 3), and every plugin
 * bundles its own H2. One driver on the classpath can therefore never open them all — instead each
 * module pins the exact version its plugin bundles ({@code h2-version}) and the matching driver jar is
 * fetched from Maven Central on first connect, cached under {@code plugins/Accounts/h2-drivers}
 * (sha256-verified for the known versions) and loaded in an isolated classloader.
 * <p>
 * Before the driver ever touches the file, the store header is sniffed and the connection refused on a
 * version/format mismatch: opening a store with the wrong driver generation is how H2 files get
 * corrupted, so the sniff is the safety core of this class. Everything is lazy — building a module from
 * a template stays offline; only an actual migration resolves the driver. Connections are opened with
 * {@code IFEXISTS=TRUE} (a typoed path must error, not create an empty store) and without a pool:
 * migrations are short bursts and H2 wants exclusive access anyway (run with the server stopped).
 */
public class H2 extends DB {

    private static final Logger LOG = Logger.getLogger(H2.class.getName());

    private static final String DRIVER_REPO = "https://repo1.maven.org/maven2/com/h2database/h2/";
    private static final byte[] PAGESTORE_MAGIC = "-- H2 0.5/B --".getBytes(StandardCharsets.US_ASCII);
    /** The pre-MVStore PageStore layout ({@code .h2.db}), reported as pseudo-format 0. */
    private static final int FORMAT_PAGESTORE = 0;

    private final String h2Version;
    private final Path driverCacheDir;
    /** Set when the owning plugin shades H2: its own jar and driver class, instead of a stock download. */
    private final String driverJar;
    private final String driverClass;
    /** Extra JDBC settings the owning plugin opens with; without them its own schema can be unreadable. */
    private final String urlOptions;

    private URLClassLoader driverLoader;
    private Driver driver;

    public H2(String host, int port, String username, String password, String database, String h2Version) {
        this(host, port, username, password, database, h2Version, null, null);
    }

    public H2(String host, int port, String username, String password, String database, String h2Version,
              String driverJar, String driverClass) {
        this(host, port, username, password, database, h2Version, driverJar, driverClass, "");
    }

    public H2(String host, int port, String username, String password, String database, String h2Version,
              String driverJar, String driverClass, String urlOptions) {
        this(host, port, username, password, database, h2Version,
                Paths.get("plugins", "Accounts", "h2-drivers"), driverJar, driverClass, urlOptions);
    }

    H2(String host, int port, String username, String password, String database, String h2Version,
       Path driverCacheDir) {
        this(host, port, username, password, database, h2Version, driverCacheDir, null, null);
    }

    H2(String host, int port, String username, String password, String database, String h2Version,
       Path driverCacheDir, String driverJar, String driverClass) {
        this(host, port, username, password, database, h2Version, driverCacheDir, driverJar, driverClass, "");
    }

    H2(String host, int port, String username, String password, String database, String h2Version,
       Path driverCacheDir, String driverJar, String driverClass, String urlOptions) {
        super(host, port, username, password, database);
        this.type = DBType.H2;
        this.h2Version = h2Version;
        this.driverCacheDir = driverCacheDir;
        this.driverJar = driverJar;
        this.driverClass = driverClass == null || driverClass.trim().isEmpty() ? "org.h2.Driver" : driverClass;
        this.urlOptions = urlOptions == null ? "" : urlOptions.trim();
    }

    @Override
    public String jdbcUrl() {
        // The configured path carries no .mv.db suffix (H2 appends it); absolute so the working
        // directory of whatever launched the migration can't change which file gets opened.
        // url-options carries whatever the owning plugin opens with — MobPuppets writes its tables into
        // a lower-case "public" schema that only DATABASE_TO_LOWER=TRUE can resolve, and a database is
        // only readable on the terms it was written under.
        String options = urlOptions.isEmpty() || urlOptions.startsWith(";") ? urlOptions : ";" + urlOptions;
        return "jdbc:h2:file:" + Paths.get(getDatabase()).toAbsolutePath() + ";IFEXISTS=TRUE" + options;
    }

    /** Informational only here: the driver is loaded from the version-pinned jar, never via Hikari. */
    @Override
    public String driverClassName() {
        return driverClass;
    }

    @Override
    public synchronized Connection getConnection() throws SQLException {
        if (driver == null) {
            checkStoredFormat();
            driver = loadDriver(resolveDriverJar());
        }
        Properties props = new Properties();
        if (getUsername() != null) {
            props.setProperty("user", getUsername());
        }
        if (getPassword() != null) {
            props.setProperty("password", getPassword());
        }
        Connection connection = driver.connect(jdbcUrl(), props);
        if (connection == null) {
            throw new SQLException("the H2 driver did not accept " + jdbcUrl());
        }
        return connection;
    }

    @Override
    public synchronized void close() {
        driver = null;
        if (driverLoader != null) {
            try {
                driverLoader.close();
            } catch (IOException e) {
                LOG.warning("could not close the H2 driver loader: " + e);
            }
            driverLoader = null;
        }
        super.close();
    }

    /** The gate: refuse to hand the file to a driver generation that did not write it. */
    private void checkStoredFormat() {
        int stored = storedFormat(getDatabase());
        int[] expected = expectedFormats(h2Version);
        for (int format : expected) {
            if (format == stored) {
                return;
            }
        }
        String file = getDatabase() + (stored == FORMAT_PAGESTORE ? ".h2.db" : ".mv.db");
        throw new MigrationException(Paths.get(file).toAbsolutePath() + " was written by H2 "
                + versionHint(stored) + ", but h2-version is \"" + h2Version
                + "\" — set h2-version to the version the owning plugin actually bundles.");
    }

    /**
     * Reads the store format from the file header without opening the database: MVStore files
     * ({@code <path>.mv.db}) carry a readable {@code format:N} field in their first block, PageStore
     * files ({@code <path>.h2.db}) start with the {@code -- H2 0.5/B --} magic.
     */
    static int storedFormat(String database) {
        Path mvStore = Paths.get(database + ".mv.db").toAbsolutePath();
        Path pageStore = Paths.get(database + ".h2.db").toAbsolutePath();
        if (Files.exists(mvStore)) {
            String header = new String(readHead(mvStore, 4096), StandardCharsets.ISO_8859_1);
            int lineEnd = header.indexOf('\n');
            if (lineEnd >= 0) {
                header = header.substring(0, lineEnd);
            }
            for (String field : header.split(",")) {
                if (field.startsWith("format:")) {
                    try {
                        return Integer.parseInt(field.substring("format:".length()));
                    } catch (NumberFormatException e) {
                        break;
                    }
                }
            }
            throw new MigrationException(mvStore + " does not look like an H2 MVStore file: no readable "
                    + "format field in its header — is the path pointing at the right file?");
        }
        if (Files.exists(pageStore)) {
            byte[] head = readHead(pageStore, PAGESTORE_MAGIC.length);
            if (Arrays.equals(head, PAGESTORE_MAGIC)) {
                return FORMAT_PAGESTORE;
            }
            throw new MigrationException(pageStore + " does not look like an H2 PageStore file "
                    + "(bad magic) — is the path pointing at the right file?");
        }
        String message = "H2 database not found: neither " + mvStore + " nor " + pageStore
                + " exists — check the module's 'database' path";
        if (database.endsWith(".mv.db") || database.endsWith(".h2.db")) {
            message += " (give it WITHOUT the .mv.db/.h2.db suffix; H2 appends that itself)";
        }
        throw new MigrationException(message);
    }

    /** Which store formats a driver of the requested version reads and writes. */
    static int[] expectedFormats(String h2Version) {
        int major;
        int minor;
        try {
            String[] parts = h2Version.split("\\.");
            major = Integer.parseInt(parts[0]);
            minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        } catch (NumberFormatException e) {
            throw new MigrationException("\"" + h2Version + "\" is not a valid h2-version "
                    + "(expected something like \"2.1.214\")", e);
        }
        if (major == 1) {
            return new int[]{FORMAT_PAGESTORE, 1};
        }
        if (major == 2 && minor <= 1) {
            return new int[]{2};
        }
        if (major == 2) {
            return new int[]{3};
        }
        throw new MigrationException("H2 " + h2Version + " is not a version family this engine knows "
                + "(1.x and 2.x only), so it cannot guarantee the file format matches — refusing.");
    }

    private static String versionHint(int format) {
        switch (format) {
            case FORMAT_PAGESTORE:
                return "1.4.x (legacy PageStore)";
            case 1:
                return "1.4.x";
            case 2:
                return "2.0.x/2.1.x (e.g. 2.1.214)";
            case 3:
                return "2.2 or newer (e.g. 2.4.240)";
            default:
                return "an unknown newer version (store format " + format + ")";
        }
    }

    private static byte[] readHead(Path file, int max) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[max];
            int read = 0;
            int n;
            while (read < max && (n = in.read(buffer, read, max - read)) > 0) {
                read += n;
            }
            return read == max ? buffer : Arrays.copyOf(buffer, read);
        } catch (IOException e) {
            throw new MigrationException("could not read the header of " + file, e);
        }
    }

    private Path resolveDriverJar() {
        if (driverJar != null && !driverJar.trim().isEmpty()) {
            return pluginDriverJar();
        }
        Path jar = driverCacheDir.resolve("h2-" + h2Version + ".jar");
        if (!Files.isRegularFile(jar)) {
            try {
                Files.createDirectories(driverCacheDir);
                downloadDriver(jar);
            } catch (IOException e) {
                throw new MigrationException("could not download the H2 " + h2Version + " driver from "
                        + "Maven Central — offline? Drop the jar at " + jar.toAbsolutePath()
                        + " manually and retry.", e);
            }
        }
        verifyChecksum(jar);
        return jar;
    }

    /**
     * The owning plugin's own H2 jar. A plugin that shades H2 writes its relocated class names into the
     * store's type metadata (GravesX files name {@code com.ranull.graves.libraries.h2...}), so a stock
     * driver reads them back as "File corrupted" — only the plugin's jar can open the file at all. Its
     * name usually carries a build hash, so the last path segment may end in {@code *}; the newest match
     * wins. No checksum here: this jar is the server's own, not something we fetched.
     */
    private Path pluginDriverJar() {
        Path configured = Paths.get(driverJar).toAbsolutePath();
        String name = configured.getFileName().toString();
        if (!name.endsWith("*")) {
            if (!Files.isRegularFile(configured)) {
                throw new MigrationException("driver-jar " + configured + " does not exist — it is the "
                        + "owning plugin's own H2 jar, which it usually writes on first start.");
            }
            return configured;
        }
        String stem = name.substring(0, name.length() - 1);
        try (java.util.stream.Stream<Path> found = Files.list(configured.getParent())) {
            return found.filter(candidate -> candidate.getFileName().toString().startsWith(stem))
                    .max(java.util.Comparator.comparingLong(candidate -> candidate.toFile().lastModified()))
                    .orElseThrow(() -> new MigrationException("no driver-jar matches " + configured
                            + " — the owning plugin writes it on first start."));
        } catch (IOException e) {
            throw new MigrationException("could not list " + configured.getParent(), e);
        }
    }

    private void downloadDriver(Path jar) throws IOException {
        URL url = new URL(DRIVER_REPO + h2Version + "/h2-" + h2Version + ".jar");
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(30_000);
        int code = connection.getResponseCode();
        if (code != 200) {
            throw new IOException("HTTP " + code + " for " + url
                    + (code == 404 ? " — is \"" + h2Version + "\" a real H2 version?" : ""));
        }
        LOG.info("downloading the H2 " + h2Version + " driver from Maven Central...");
        AtomicFiles.write(jar, tmp -> {
            try (InputStream in = connection.getInputStream()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
        });
    }

    private void verifyChecksum(Path jar) {
        String pinned = pinnedSha256(h2Version);
        if (pinned == null) {
            LOG.warning("no pinned sha256 for H2 " + h2Version + " — skipping verification of " + jar);
            return;
        }
        String actual = sha256(jar);
        if (!pinned.equals(actual)) {
            throw new MigrationException("sha256 mismatch for " + jar.toAbsolutePath() + ": expected "
                    + pinned + ", found " + actual + " — delete the file and retry; if it keeps "
                    + "happening, something is tampering with the download.");
        }
    }

    /** Pins for the versions the shipped templates use, verified against Maven Central's checksums. */
    private static String pinnedSha256(String version) {
        switch (version) {
            case "1.4.197": // LiteBans
                return "37f5216e14af2772930dff9b8734353f0a80e89ba3f33e065441de6537c5e842";
            case "1.4.200": // Parties
                return "3ad9ac4b6aae9cd9d3ac1c447465e1ed06019b851b893dd6a8d76ddb6d85bca6";
            case "2.1.214": // AxVaults
                return "d623cdc0f61d218cf549a8d09f1c391ff91096116b22e2475475fce4fbe72bd0";
            case "2.2.224": // MobPuppets
                return "b9d8f19358ada82a4f6eb5b174c6cfe320a375b5a9cb5a4fe456d623e6e55497";
            case "2.3.232": // TicketManager (TMSE)
                return "8dae62d22db8982c3dcb3826edb9c727c5d302063a67eef7d63d82de401f07d3";
            case "2.4.240": // GravesX
                return "29b70e427cc1c40cdc376283adbb0cc62853073797bb5fe5761f81fe73d57ce0";
            default:
                return null;
        }
    }

    private static String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (IOException e) {
            throw new MigrationException("could not hash " + file, e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JRE", e);
        }
    }

    private Driver loadDriver(Path jar) {
        try {
            // Parent = the JDK-only loader (extension on Java 8, platform on 9+): java.sql stays
            // visible, but nothing from the application classpath — such as another plugin's H2 —
            // can leak into this loader, and this jar's org.h2 never leaks out.
            ClassLoader parent = ClassLoader.getSystemClassLoader().getParent();
            driverLoader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, parent);
            // Instantiated directly and used as a plain java.sql.Driver: DriverManager would filter
            // it out again, because it only hands drivers to callers that share their classloader.
            return (Driver) Class.forName(driverClass, true, driverLoader)
                    .getDeclaredConstructor().newInstance();
        } catch (IOException | ReflectiveOperationException e) {
            throw new MigrationException("could not load " + driverClass + " from " + jar.toAbsolutePath(), e);
        }
    }
}
