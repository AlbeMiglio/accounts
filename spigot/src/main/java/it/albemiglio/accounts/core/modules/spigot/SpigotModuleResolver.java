package it.albemiglio.accounts.core.modules.spigot;

import it.albemiglio.accounts.core.modules.ModuleResolver;
import it.albemiglio.accounts.core.services.ModuleService;

import java.util.Set;

public class SpigotModuleResolver extends ModuleResolver<SpigotModule> {

    public SpigotModuleResolver(ModuleService moduleService) {
        super(moduleService);
    }

    @Override
    public Set<SpigotModule> fetchModules() {
        return null;
    }
}
