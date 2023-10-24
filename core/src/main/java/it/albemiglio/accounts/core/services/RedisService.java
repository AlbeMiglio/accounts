package it.albemiglio.accounts.core.services;

import lombok.Getter;
import lombok.Setter;

import java.util.HashSet;
import java.util.Set;

@Getter
public class RedisService implements IService {

    private final Set<String> pubChannels;
    private final Set<String> subChannels;
    @Setter
    private boolean running;

    public RedisService() {
        this.pubChannels = new HashSet<>();
        this.subChannels = new HashSet<>();
    }

    public void start() {
    }

    public void end() {
    }
}
