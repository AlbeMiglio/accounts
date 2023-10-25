package it.albemiglio.accounts.core.redis;

import lombok.Getter;
import redis.clients.jedis.JedisPubSub;

import java.lang.reflect.Method;

public class ListenerHandler extends JedisPubSub {
    private Method method;
    @Getter
    private String channel;

    public ListenerHandler(String channel, Method method) {
        this.channel = channel;
        this.method = method;
    }

    @Override
    public void onMessage(String channel, String message) {
        try {
            method.invoke(null, message);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
