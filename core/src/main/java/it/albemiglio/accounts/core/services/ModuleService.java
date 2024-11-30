package it.albemiglio.accounts.core.services;

import java.util.concurrent.ConcurrentHashMap;
import it.albemiglio.accounts.core.modules.Module;

public class ModuleService {

    private ConcurrentHashMap<String, Module> modules;
    private RedisService redisService;

    public ModuleService(RedisService redisService) {
        modules = new ConcurrentHashMap<>();
        this.redisService = redisService;
    }

    public void loadModules() {

        // module loading logic
        // resolve modules using resolvers, then load them

        int activeModules = modules.reduceValuesToInt(10, m -> m.isEnabled() ? 1 : 0, 0, Integer::sum);
        this.redisService.updateActiveModules(activeModules);
    }

    public void unloadModules() {

    }

    public void reloadModules() {

    }
}
