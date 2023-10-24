package it.albemiglio.accounts.core.database;

import it.albemiglio.accounts.core.objects.enums.DBType;

public abstract class DB {

    // TODO: aggiungi tutti i dati generici per collegarsi a un qualsiasi tipo di database

    private String host;
    private int port;
    private String username;
    private String password;
    private String database;

    protected DBType type;

    public DB(String host, int port, String username, String password, String database) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.database = database;
    }
}
