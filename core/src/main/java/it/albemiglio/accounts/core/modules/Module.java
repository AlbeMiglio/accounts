package it.albemiglio.accounts.core.modules;

import it.albemiglio.accounts.core.database.DB;
import it.albemiglio.accounts.core.modules.replacers.Replacer;
import it.albemiglio.accounts.core.objects.enums.Platform;

import java.io.File;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

public abstract class Module {

    private final String name;
    private final Platform platform;
    private boolean running;
    private boolean enabled;
    private Optional<String> pluginName;

    private Set<Replacer> replacers;

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
        this.pluginName = Optional.empty();
        this.replacers = new HashSet<>();
    }

    public void enable() {
        this.enabled = true;
    }

    public void disable() {
        this.enabled = false;
    }

    public void reload() {}
}
