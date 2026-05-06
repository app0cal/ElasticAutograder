package com.autograder.service.job;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    @PreDestroy
    public void stop() {
        running.set(false);
        if (pollingThread != null) {
            pollingThread.interrupt();
        }
    }

    boolean pollOnce() {
        String payload = redisTemplate.opsForList()
                .rightPop(queueProperties.getName(), workerProperties.getPollTimeout());
        if (payload == null) {
            return false;
        }

        submitPayload(payload);
        return true;
    }

    void submitPayload(String payload) {
        try {
            GradingJobMessage message = objectMapper.readValue(payload, GradingJobMessage.class);
            if (message.jobId() == null) {
                logger.warn("Skipping grading queue message without jobId.");
                return;
            }

            logger.info(
                    "Worker consumed grading job jobId={} institutionId={} attempt={} workerId={}",
                    message.jobId(),
                    message.institutionId(),
                    message.attempt(),
                    workerId
            );
            gradingTaskExecutor.execute(() -> jobExecutionService.executeQueuedJob(message.jobId(), workerId));
        } catch (Exception e) {
            logger.warn("Skipping malformed grading queue message: {}", e.getMessage());
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
