package it.albemiglio.accounts.core.services;

import java.util.concurrent.ConcurrentHashMap;

public class ModuleService {

    private ConcurrentHashMap<String, Module> modules;

    public ModuleService() {
        modules = new ConcurrentHashMap<>();
    }

    public void loadModules() {

    }

    public void unloadModules() {

    }

    public void reloadModules() {

    }
}
