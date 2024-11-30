package it.albemiglio.accounts.core.objects;

import lombok.Data;

import java.util.Objects;
import java.util.UUID;

@Data
public class Task {

    private Pair<UUID, UUID> migration;
    private String username;
    private int currFailures;


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Task task = (Task) o;

        if (!Objects.equals(migration, task.migration)) return false;
        return Objects.equals(username, task.username);
    }

    @Override
    public String toString() {
        return migration.getLeft() + ";" + migration.getRight() + ";" + username + ";" + currFailures;
    }

    public static Task fromString(String task) {
        Task t = new Task();
        String[] parts = task.split(";");
        t.setMigration(new Pair<>(UUID.fromString(parts[0]), UUID.fromString(parts[1])));
        t.setUsername(parts[2]);
        t.setCurrFailures(Integer.parseInt(parts[3]));
        return t;
    }
}
