package com.autograder.service.system;

import java.util.List;
import java.util.Map;

import com.autograder.model.JobStatus;

public record QueueHealthResponse(
        boolean redisConnected,
        String redisError,
        String queueName,
        long queueDepth,
        boolean workerEnabled,
        int workerConcurrency,
        String kubernetesNamespace,
        Integer maxActiveKubernetesJobs,
        Map<JobStatus, Long> jobCounts,
        List<QueueHealthJobSummary> recentRunningJobs,
        List<QueueHealthJobSummary> staleRunningJobs,
        List<QueueHealthJobSummary> recentDeadLetteredJobs
) {
}
