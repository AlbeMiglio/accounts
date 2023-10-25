package it.albemiglio.accounts.core.redis;

public interface RedisListener {

    default void onMessageReceived(String message) {

    }
}
