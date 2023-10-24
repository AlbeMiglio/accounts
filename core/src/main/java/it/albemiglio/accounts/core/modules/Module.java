package it.albemiglio.accounts.core.modules;

import it.albemiglio.accounts.core.database.DB;
import it.albemiglio.accounts.core.objects.enums.Platform;

import java.io.File;
import java.util.Optional;
import java.util.Set;

public abstract class Module {

    private String name;
    private Platform platform;
    private boolean running;
    private boolean enabled;
    private Optional<String> pluginName;

    /**
     * The module will open these files and read them to replace UUIDs
     */
    private File[] files;

    /**
     * The module will explore recursively these folders and look for files/folders names to replace UUIDs in them.
     * (e.g. fant-ast-ic-uuid.json ---> myn-ews-uper-uuid.json)
     */
    private File[] folders;

    private Set<DB> databases;

    /**
     *
     * @param name
     * @param platform
     */
    public Module(String name, Platform platform) {
        this.name = name;
        this.platform = platform;
        this.running = false;
        this.enabled = false;
    }

    public void enable() {
        this.enabled = true;
    }

    public void disable() {
        this.enabled = false;
    }

    public void reload() {}
}
