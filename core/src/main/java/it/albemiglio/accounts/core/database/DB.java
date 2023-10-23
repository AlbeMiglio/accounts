package it.albemiglio.accounts.core.database;

public abstract class DB {

    // aggiungi qui tutti i dati generici astratti per collegarsi a un qualsiasi tipo di database

    private String driver;
    private String host;
    private int port;
    private String username;
    private String password;
    private String database;

    public DB(String host, int port, String username, String password, String database) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.database = database;
    }
}
