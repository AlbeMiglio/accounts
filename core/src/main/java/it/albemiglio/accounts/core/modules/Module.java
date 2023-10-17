package it.albemiglio.accounts.core.modules;

import it.albemiglio.accounts.core.objects.enums.Platform;

import java.util.Optional;

public abstract class Module {

    private String name;
    private Platform platform;
    private boolean running;
    private boolean enabled;
    private Optional<String> pluginName;

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
}
