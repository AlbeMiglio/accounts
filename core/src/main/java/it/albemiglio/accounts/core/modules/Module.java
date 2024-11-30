package it.albemiglio.accounts.core.modules;

import it.albemiglio.accounts.core.database.DB;
import it.albemiglio.accounts.core.modules.replacers.Replacer;
import it.albemiglio.accounts.core.objects.Pair;
import it.albemiglio.accounts.core.objects.enums.Platform;
import lombok.Getter;

import java.io.File;
import java.util.*;

public abstract class Module {

    private final String name;
    private final Platform platform;
    private boolean running;
    @Getter
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

    public void execute(Pair<UUID, UUID> task) {

    }

    public void executeBatch(Collection<Pair<UUID, UUID>> tasks) {
        tasks.forEach(this::execute);
    }
}
