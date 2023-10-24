package it.albemiglio.accounts.spigot;

import it.albemiglio.accounts.core.ModuleResolver;
import it.albemiglio.accounts.core.modules.SpigotModule;
import it.albemiglio.accounts.core.services.ModuleService;

import java.util.Set;

public class SpigotModuleResolver extends ModuleResolver<SpigotModule> {

    public SpigotModuleResolver(ModuleService moduleService) {
        super(moduleService);
    }

    @Override
    public Set<SpigotModule> fetchModules() {

    }
}
