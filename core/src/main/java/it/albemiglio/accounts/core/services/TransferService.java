package it.albemiglio.accounts.core.services;

import it.albemiglio.accounts.core.objects.Task;

import java.util.HashSet;
import java.util.Set;

public class TransferService implements IService {

    private Set<Task> tasks;

    public TransferService() {
        this.tasks = new HashSet<>();
    }

    @Override
    public void start() {

    }

    @Override
    public void end() {

    }

    @Override
    public boolean isRunning() {
        return false;
    }
}
