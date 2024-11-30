package it.albemiglio.accounts.core.services;

import it.albemiglio.accounts.core.objects.Task;
import it.albemiglio.accounts.core.objects.enums.FailureCause;
import it.albemiglio.accounts.core.redis.Channel;
import it.albemiglio.accounts.core.redis.ListenerHandler;
import it.albemiglio.accounts.core.redis.RedisListener;
import lombok.Getter;
import lombok.Setter;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.SetParams;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;


public class RedisService implements IService {

    private final ConcurrentHashMap<ListenerHandler, Thread> listeners;
    @Getter
    private boolean running;
    private Jedis jedis;
    private static final int LEADER_TTL = 30;
    @Getter
    private final String instanceId;

    public RedisService() {
        this.listeners = new ConcurrentHashMap<>();
        this.instanceId = "instance-" + System.currentTimeMillis();
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

    public void send(String channel, String message) {
        jedis.publish(channel, message);
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
        this.removeInstance();
        jedis.close();
    }

    public boolean trySetLeader() {
        SetParams setParams = new SetParams().nx().ex(LEADER_TTL);
        String result = jedis.set("leader-key", instanceId, setParams);
        return "OK".equals(result);
    }

    public String getLeader() {
        return jedis.get("leader-key");
    }

    public void extendLeaderTTL() {
        jedis.expire("leader-key", LEADER_TTL);
    }

    public void clearLeader() {
        jedis.del("leader-key");
    }

    public void addToQueue(Task task) {
        jedis.rpush("uuids-waiting-queue", task.toString());
    }

    public Task popFromQueue() {
        return Task.fromString(jedis.lpop("uuids-waiting-queue"));
    }

    public long queueSize() {
        return jedis.llen("uuids-waiting-queue");
    }

    public void updateActiveModules(int moduleCount) {
        jedis.hset("active-modules", instanceId, String.valueOf(moduleCount));
    }

    public void removeInstance() {
        jedis.hdel("active-modules", instanceId);
    }

    public void addFailedOperation(String operation, FailureCause cause) {
        jedis.hset("failed-operations", operation + "@" + instanceId, cause.name());
    }

    public boolean hasFailedOperation(String operation) {
        return jedis.hkeys("failed-operations").stream().anyMatch(k -> k.startsWith(operation + "@"));
    }

    public void clearFailedOperations() {
        jedis.del("failed-operations");
    }

    public Map<String, String> getFailedOperations() {
        return jedis.hgetAll("failed-operations");
    }
}
