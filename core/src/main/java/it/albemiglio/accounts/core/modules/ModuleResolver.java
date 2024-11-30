package it.albemiglio.accounts.core.modules;

import it.albemiglio.accounts.core.services.ModuleService;

import java.util.Set;

public abstract class ModuleResolver<T extends Module> {

    private final ModuleService moduleService;

    public ModuleResolver(ModuleService moduleService) {
        this.moduleService = moduleService;
    }

    public abstract Set<T> fetchModules();
        //TODO: look for serverFolder/plugins/accounts/jar-modules and load all jar modules;
        //      then look for serverFolder/plugins/accounts/yaml-modules and build them (then load);
        //      now look for package modules and load all of them;
}
