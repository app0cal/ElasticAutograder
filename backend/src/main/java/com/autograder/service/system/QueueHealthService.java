package com.autograder.service.system;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.autograder.config.KubernetesGradingProperties;
import com.autograder.config.QueueHealthProperties;
import com.autograder.config.QueueProperties;
import com.autograder.config.WorkerProperties;
import com.autograder.model.JobStatus;
import com.autograder.repository.JobRepository;

@Service
public class QueueHealthService {

    private final StringRedisTemplate redisTemplate;
    private final JobRepository jobRepository;
    private final QueueProperties queueProperties;
    private final WorkerProperties workerProperties;
    private final KubernetesGradingProperties kubernetesProperties;
    private final QueueHealthProperties queueHealthProperties;
    private final Clock clock;

    @Autowired
    public QueueHealthService(
            StringRedisTemplate redisTemplate,
            JobRepository jobRepository,
            QueueProperties queueProperties,
            WorkerProperties workerProperties,
            KubernetesGradingProperties kubernetesProperties,
            QueueHealthProperties queueHealthProperties
    ) {
        this(
                redisTemplate,
                jobRepository,
                queueProperties,
                workerProperties,
                kubernetesProperties,
                queueHealthProperties,
                Clock.systemDefaultZone()
        );
    }

    QueueHealthService(
            StringRedisTemplate redisTemplate,
            JobRepository jobRepository,
            QueueProperties queueProperties,
            WorkerProperties workerProperties,
            KubernetesGradingProperties kubernetesProperties,
            QueueHealthProperties queueHealthProperties,
            Clock clock
    ) {
        this.redisTemplate = redisTemplate;
        this.jobRepository = jobRepository;
        this.queueProperties = queueProperties;
        this.workerProperties = workerProperties;
        this.kubernetesProperties = kubernetesProperties;
        this.queueHealthProperties = queueHealthProperties;
        this.clock = clock;
    }

    public QueueHealthResponse getQueueHealth() {
        RedisHealth redisHealth = readRedisHealth();
        OffsetDateTime staleCutoff = OffsetDateTime.now(clock).minus(queueHealthProperties.getStaleRunningThreshold());

        return new QueueHealthResponse(
                redisHealth.connected(),
                redisHealth.error(),
                queueProperties.getName(),
                redisHealth.queueDepth(),
                workerProperties.isEnabled(),
                workerProperties.getConcurrency(),
                kubernetesProperties.getNamespace(),
                kubernetesProperties.getMaxActiveJobs(),
                readJobCounts(),
                jobRepository.findTop10ByStatusOrderByStartedAtDesc(JobStatus.RUNNING)
                        .stream()
                        .map(QueueHealthJobSummary::fromJob)
                        .toList(),
                jobRepository.findStaleRunningJobs(staleCutoff)
                        .stream()
                        .map(QueueHealthJobSummary::fromJob)
                        .toList(),
                jobRepository.findTop10ByStatusOrderByUpdatedAtDesc(JobStatus.DEAD_LETTERED)
                        .stream()
                        .map(QueueHealthJobSummary::fromJob)
                        .toList()
        );
    }

    private RedisHealth readRedisHealth() {
        try {
            Long size = redisTemplate.opsForList().size(queueProperties.getName());
            return new RedisHealth(true, null, size == null ? 0L : size);
        } catch (RuntimeException e) {
            return new RedisHealth(false, e.getMessage(), 0L);
        }
    }

    private Map<JobStatus, Long> readJobCounts() {
        Map<JobStatus, Long> counts = new EnumMap<>(JobStatus.class);
        for (JobStatus status : JobStatus.values()) {
            counts.put(status, 0L);
        }
        for (JobRepository.JobStatusCount count : jobRepository.countJobsByStatus()) {
            counts.put(count.getStatus(), count.getCount());
        }
        return counts;
    }

    private record RedisHealth(boolean connected, String error, long queueDepth) {
    }
}
