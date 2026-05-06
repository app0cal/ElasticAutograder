package com.autograder.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;

import com.autograder.model.JobStatus;
import com.autograder.service.system.QueueHealthResponse;
import com.autograder.service.system.QueueHealthService;

class SystemHealthControllerTest {

    private final QueueHealthService queueHealthService = Mockito.mock(QueueHealthService.class);
    private final SystemHealthController controller = new SystemHealthController(queueHealthService);

    @Test
    void getQueueHealth_returnsServiceHealthDto() {
        Map<JobStatus, Long> jobCounts = new EnumMap<>(JobStatus.class);
        jobCounts.put(JobStatus.QUEUED, 2L);
        QueueHealthResponse health = new QueueHealthResponse(
                true,
                null,
                "grading-jobs",
                5L,
                true,
                4,
                "elastic-grading",
                8,
                jobCounts,
                List.of(),
                List.of(),
                List.of()
        );
        when(queueHealthService.getQueueHealth()).thenReturn(health);

        ResponseEntity<QueueHealthResponse> response = controller.getQueueHealth();

        assertEquals(200, response.getStatusCode().value());
        assertEquals(health, response.getBody());
    }
}
