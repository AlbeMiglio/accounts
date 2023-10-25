package it.albemiglio.accounts.core.services;

import it.albemiglio.accounts.core.redis.Channel;
import it.albemiglio.accounts.core.redis.ListenerHandler;
import it.albemiglio.accounts.core.redis.RedisListener;
import lombok.Getter;
import lombok.Setter;
import redis.clients.jedis.Jedis;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Getter
public class RedisService implements IService {

    private final ConcurrentHashMap<ListenerHandler, Thread> listeners;
    @Setter
    private boolean running;
    private Jedis jedis;

    public RedisService() {
        this.listeners = new ConcurrentHashMap<>();
    }

    public void register(RedisListener listener) {
        Class<?> listenerClass = listener.getClass();
        for (Method method : listenerClass.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Channel.class) && method.getParameterCount() == 1) {
                Channel channelAnnotation = method.getAnnotation(Channel.class);
                String channelName = channelAnnotation.value();
                ListenerHandler handler = new ListenerHandler(channelName, method);
                Thread thread = new Thread(() -> jedis.subscribe(handler, channelName));
                thread.start();
                this.listeners.put(handler, thread);
            }
        }
    }

    public void unregisterAll() {
        Set<ListenerHandler> handlers = new HashSet<>(this.listeners.keySet());
        for (ListenerHandler handler : handlers) {
            Thread thread = this.listeners.get(handler);
            thread.interrupt();
            this.listeners.remove(handler);
        }
    }

    public void start() {
        jedis = new Jedis("localhost");
    }

    public void end() {
        this.unregisterAll();
        jedis.close();
    }
}
