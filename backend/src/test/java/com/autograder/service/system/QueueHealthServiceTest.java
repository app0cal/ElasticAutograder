package com.autograder.service.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.autograder.config.KubernetesGradingProperties;
import com.autograder.config.QueueHealthProperties;
import com.autograder.config.QueueProperties;
import com.autograder.config.WorkerProperties;
import com.autograder.model.Job;
import com.autograder.model.JobStatus;
import com.autograder.repository.JobRepository;

class QueueHealthServiceTest {

    private StringRedisTemplate redisTemplate;
    private ListOperations<String, String> listOperations;
    private JobRepository jobRepository;
    private QueueProperties queueProperties;
    private WorkerProperties workerProperties;
    private KubernetesGradingProperties kubernetesProperties;
    private QueueHealthProperties queueHealthProperties;
    private QueueHealthService service;
    private Clock clock;

    @BeforeEach
    void setUp() {
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        listOperations = Mockito.mock(ListOperations.class);
        jobRepository = Mockito.mock(JobRepository.class);
        queueProperties = new QueueProperties();
        workerProperties = new WorkerProperties();
        kubernetesProperties = new KubernetesGradingProperties();
        queueHealthProperties = new QueueHealthProperties();
        clock = Clock.fixed(Instant.parse("2026-05-05T12:00:00Z"), ZoneOffset.UTC);
        service = new QueueHealthService(
                redisTemplate,
                jobRepository,
                queueProperties,
                workerProperties,
                kubernetesProperties,
                queueHealthProperties,
                clock
        );

        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(jobRepository.countJobsByStatus()).thenReturn(List.of());
        when(jobRepository.findTop10ByStatusOrderByStartedAtDesc(JobStatus.RUNNING)).thenReturn(List.of());
        when(jobRepository.findStaleRunningJobs(OffsetDateTime.parse("2026-05-05T11:55:00Z"))).thenReturn(List.of());
        when(jobRepository.findTop10ByStatusOrderByUpdatedAtDesc(JobStatus.DEAD_LETTERED)).thenReturn(List.of());
    }

    @Test
    void getQueueHealth_redisAvailable_returnsQueueDepthAndSettings() {
        workerProperties.setConcurrency(7);
        kubernetesProperties.setNamespace("elastic-grading");
        kubernetesProperties.setMaxActiveJobs(9);
        when(listOperations.size("grading-jobs")).thenReturn(12L);

        QueueHealthResponse response = service.getQueueHealth();

        assertTrue(response.redisConnected());
        assertNull(response.redisError());
        assertEquals("grading-jobs", response.queueName());
        assertEquals(12L, response.queueDepth());
        assertTrue(response.workerEnabled());
        assertEquals(7, response.workerConcurrency());
        assertEquals("elastic-grading", response.kubernetesNamespace());
        assertEquals(9, response.maxActiveKubernetesJobs());
    }

    @Test
    void getQueueHealth_redisUnavailable_returnsDatabaseHealth() {
        when(redisTemplate.opsForList()).thenThrow(new RedisConnectionFailureException("redis down"));
        when(jobRepository.countJobsByStatus()).thenReturn(List.of(statusCount(JobStatus.QUEUED, 3L)));

        QueueHealthResponse response = service.getQueueHealth();

        assertFalse(response.redisConnected());
        assertEquals("redis down", response.redisError());
        assertEquals(0L, response.queueDepth());
        assertEquals(3L, response.jobCounts().get(JobStatus.QUEUED));
    }

    @Test
    void getQueueHealth_mapsMissingJobCountsToZero() {
        when(listOperations.size("grading-jobs")).thenReturn(0L);
        when(jobRepository.countJobsByStatus()).thenReturn(List.of(
                statusCount(JobStatus.QUEUED, 10L),
                statusCount(JobStatus.RUNNING, 4L),
                statusCount(JobStatus.DEAD_LETTERED, 1L)
        ));

        QueueHealthResponse response = service.getQueueHealth();

        assertEquals(10L, response.jobCounts().get(JobStatus.QUEUED));
        assertEquals(4L, response.jobCounts().get(JobStatus.RUNNING));
        assertEquals(1L, response.jobCounts().get(JobStatus.DEAD_LETTERED));
        assertEquals(0L, response.jobCounts().get(JobStatus.SUCCEEDED));
    }

    @Test
    void getQueueHealth_returnsStaleRunningJobsOlderThanThreshold() throws Exception {
        OffsetDateTime staleCutoff = OffsetDateTime.parse("2026-05-05T11:55:00Z");
        Job stale = job(44L, JobStatus.RUNNING);
        stale.setGraderType("fib");
        stale.setInstitutionId("local");
        stale.setSubmittedBy("anonymous");
        stale.setWorkerId("worker-abc");
        stale.setStartedAt(staleCutoff.minusMinutes(2));
        stale.setLastAttemptAt(staleCutoff.minusMinutes(1));
        stale.setAttemptCount(2);
        stale.setMaxAttempts(3);
        when(listOperations.size("grading-jobs")).thenReturn(0L);
        when(jobRepository.findStaleRunningJobs(staleCutoff)).thenReturn(List.of(stale));

        QueueHealthResponse response = service.getQueueHealth();

        assertEquals(1, response.staleRunningJobs().size());
        QueueHealthJobSummary summary = response.staleRunningJobs().get(0);
        assertEquals(44L, summary.id());
        assertEquals("fib", summary.graderType());
        assertEquals("local", summary.institutionId());
        assertEquals("anonymous", summary.submittedBy());
        assertEquals("worker-abc", summary.workerId());
        assertEquals(2, summary.attemptCount());
        assertEquals(3, summary.maxAttempts());
    }

    @Test
    void getQueueHealth_returnsRecentRunningJobsWithWorkerIds() throws Exception {
        Job running = job(45L, JobStatus.RUNNING);
        running.setGraderType("fib");
        running.setInstitutionId("local");
        running.setSubmittedBy("anonymous");
        running.setWorkerId("compose-worker-a");
        running.setStartedAt(OffsetDateTime.parse("2026-05-05T11:59:30Z"));
        running.setAttemptCount(1);
        running.setMaxAttempts(3);
        when(listOperations.size("grading-jobs")).thenReturn(0L);
        when(jobRepository.findTop10ByStatusOrderByStartedAtDesc(JobStatus.RUNNING)).thenReturn(List.of(running));

        QueueHealthResponse response = service.getQueueHealth();

        assertEquals(1, response.recentRunningJobs().size());
        QueueHealthJobSummary summary = response.recentRunningJobs().get(0);
        assertEquals(45L, summary.id());
        assertEquals("compose-worker-a", summary.workerId());
        assertEquals(JobStatus.RUNNING, summary.status());
    }

    private JobRepository.JobStatusCount statusCount(JobStatus status, long count) {
        return new JobRepository.JobStatusCount() {
            @Override
            public JobStatus getStatus() {
                return status;
            }

            @Override
            public long getCount() {
                return count;
            }
        };
    }

    private Job job(Long id, JobStatus status) throws Exception {
        Job job = new Job("submission.py", "fib", OffsetDateTime.now(clock), status);
        Field idField = Job.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(job, id);
        return job;
    }
}
