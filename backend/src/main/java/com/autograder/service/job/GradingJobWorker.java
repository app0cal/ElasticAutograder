package com.autograder.service.job;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;
import java.util.concurrent.Semaphore;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.autograder.config.QueueProperties;
import com.autograder.config.WorkerProperties;

/**
 * Redis consumer that hands queued grading messages to the local worker pool.
 */
@Service
public class GradingJobWorker {

    private static final Logger logger = LoggerFactory.getLogger(GradingJobWorker.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final QueueProperties queueProperties;
    private final WorkerProperties workerProperties;
    private final TaskExecutor gradingTaskExecutor;
    private final JobExecutionService jobExecutionService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Semaphore executionPermits;
    private final String workerId;
    private Thread pollingThread;

    public GradingJobWorker(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            QueueProperties queueProperties,
            WorkerProperties workerProperties,
            @Qualifier("gradingTaskExecutor") TaskExecutor gradingTaskExecutor,
            JobExecutionService jobExecutionService
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.queueProperties = queueProperties;
        this.workerProperties = workerProperties;
        this.gradingTaskExecutor = gradingTaskExecutor;
        this.jobExecutionService = jobExecutionService;
        this.executionPermits = new Semaphore(Math.max(1, workerProperties.getConcurrency()));
        this.workerId = buildWorkerId(workerProperties);
    }

    /**
     * Starts the background poll loop after the app is ready.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!shouldStart() || !running.compareAndSet(false, true)) {
            return;
        }

        pollingThread = new Thread(this::pollLoop, "grading-redis-poller");
        pollingThread.start();
        logger.info(
                "Started Redis grading worker queue={} concurrency={} workerId={}",
                queueProperties.getName(),
                workerProperties.getConcurrency(),
                workerId
        );
    }

    boolean shouldStart() {
        return queueProperties.isEnabled() && workerProperties.isEnabled();
    }

    boolean isRunning() {
        return running.get();
    }

    String getWorkerId() {
        return workerId;
    }

    int availableExecutionPermits() {
        return executionPermits.availablePermits();
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (pollingThread != null) {
            pollingThread.interrupt();
        }
    }

    boolean pollOnce() {
        if (!acquireExecutionPermit()) {
            return false;
        }

        String payload = redisTemplate.opsForList()
                .rightPop(queueProperties.getName(), workerProperties.getPollTimeout());
        if (payload == null) {
            executionPermits.release();
            return false;
        }

        submitPayload(payload, true);
        return true;
    }

    void submitPayload(String payload) {
        if (!acquireExecutionPermit()) {
            requeuePayload(payload, "worker interrupted before task submission");
            return;
        }

        submitPayload(payload, true);
    }

    private void submitPayload(String payload, boolean permitHeld) {
        try {
            GradingJobMessage message = objectMapper.readValue(payload, GradingJobMessage.class);
            if (message.jobId() == null) {
                logger.warn("Skipping grading queue message without jobId.");
                releasePermitIfHeld(permitHeld);
                return;
            }

            logger.info(
                    "Worker consumed grading job jobId={} institutionId={} attempt={} workerId={}",
                    message.jobId(),
                    message.institutionId(),
                    message.attempt(),
                    workerId
            );
            gradingTaskExecutor.execute(() -> {
                try {
                    jobExecutionService.executeQueuedJob(message.jobId(), workerId);
                } finally {
                    releasePermitIfHeld(permitHeld);
                }
            });
        } catch (JsonProcessingException e) {
            releasePermitIfHeld(permitHeld);
            logger.warn("Skipping malformed grading queue message: {}", e.getMessage());
        } catch (RuntimeException e) {
            releasePermitIfHeld(permitHeld);
            requeuePayload(payload, "task executor rejected grading job: " + e.getMessage());
        }
    }

    private boolean acquireExecutionPermit() {
        try {
            executionPermits.acquire();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void releasePermitIfHeld(boolean permitHeld) {
        if (permitHeld) {
            executionPermits.release();
        }
    }

    private void requeuePayload(String payload, String reason) {
        try {
            redisTemplate.opsForList().leftPush(queueProperties.getName(), payload);
            logger.warn("Requeued grading queue message after failed dispatch: {}", reason);
        } catch (RuntimeException requeueFailure) {
            logger.error("Failed to requeue grading queue message after failed dispatch: {}", requeueFailure.getMessage());
        }
    }

    private void pollLoop() {
        while (running.get()) {
            try {
                pollOnce();
            } catch (Exception e) {
                logger.warn("Redis grading queue poll failed: {}", e.getMessage());
                sleepAfterFailure();
            }
        }
    }

    private void sleepAfterFailure() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String buildWorkerId(WorkerProperties properties) {
        String prefix = properties.getIdPrefix();
        if (prefix == null || prefix.isBlank()) {
            prefix = "worker";
        }
        return prefix + "-" + UUID.randomUUID();
    }
}
