package it.albemiglio.accounts.core.services;

import it.albemiglio.accounts.core.objects.Task;

import java.util.concurrent.ConcurrentLinkedQueue;

public class BatchService {
    private final RedisService redisService;

    private final ConcurrentLinkedQueue<Task> localQueue = new ConcurrentLinkedQueue<>();
    private boolean isProcessing = false;
    private static final int TTL_EXTENSION_INTERVAL = 10;
    private final String instanceId;

    public BatchService(RedisService redisService) {
        this.redisService = redisService;
        this.instanceId = redisService.getInstanceId();
    }

    // future API methods will call this method to handle migrations
    public synchronized void handleRequest(Task task) {
        redisService.addToQueue(task);

        if (!isProcessing && (redisService.getLeader() == null || redisService.getLeader().isEmpty())) {
            if (redisService.trySetLeader()) {
                startNewBatch();
            }
        }
    }

    private void startNewBatch() {
        isProcessing = true;

        startTTLUpdater();

        new Thread(() -> {
            try {
                // wait some seconds to allow instances to fill the common queue for a little more
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            if (instanceId.equals(redisService.getLeader())) {
                fillLocalQueueFromRedis();
                processBatch();
            } else {
                // no more leader -> stop processing
                isProcessing = false;
            }
        }).start();
    }

    private void fillLocalQueueFromRedis() {
        while (redisService.queueSize() > 0) {
            Task task = redisService.popFromQueue();
            if (task != null) {
                localQueue.add(task);
            }
        }
    }

    private void processBatch() {
        while (!localQueue.isEmpty()) {
            Task task = localQueue.poll();

            // task performing logic
        }


        // reschedule failed operations from redis service, reverting them and putting them on redis queue

        if (redisService.queueSize() > 0 && instanceId.equals(redisService.getLeader())) {
            // restart processing in case of new tasks (or previous reverted ones)
            startNewBatch();
        }
        else {
            redisService.clearLeader();
            isProcessing = false;
        }
    }

    private void startTTLUpdater() {
        new Thread(() -> {
            while (isProcessing) {
                try {
                    Thread.sleep(TTL_EXTENSION_INTERVAL * 1000);
                    if (instanceId.equals(redisService.getLeader())) {
                        redisService.extendLeaderTTL();
                    } else {
                        break;
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }
}
