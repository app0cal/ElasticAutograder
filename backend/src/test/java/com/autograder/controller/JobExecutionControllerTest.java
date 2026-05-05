package com.autograder.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.autograder.service.identity.RequestIdentityProvider;
import com.autograder.service.job.JobEnqueueResponse;
import com.autograder.service.job.JobExecutionService;
import com.autograder.service.job.JobIntakeUnavailableException;
import com.autograder.service.job.JobNotFoundException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.StringNode;

class JobExecutionControllerTest {

    private final JobExecutionService jobExecutionService = Mockito.mock(JobExecutionService.class);
    private final JobExecutionController controller = new JobExecutionController(
            jobExecutionService,
            new RequestIdentityProvider()
    );
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void runJob_queuedJob_returnsAcceptedMessage() {
        when(jobExecutionService.enqueueJob(any(), any()))
                .thenReturn(new JobEnqueueResponse(HttpStatus.ACCEPTED, new StringNode("Job 1 queued.")));

        ResponseEntity<JsonNode> response = controller.runJob(1L, "\"submission.py\"", null, null);

        assertEquals(202, response.getStatusCode().value());
        assertEquals("Job 1 queued.", response.getBody().asText());
    }

    @Test
    void runJob_missingJob_returns404() {
        when(jobExecutionService.enqueueJob(any(), any()))
                .thenThrow(new JobNotFoundException("Unable to find job object for id 404"));

        ResponseEntity<JsonNode> response = controller.runJob(404L, "\"submission.py\"", null, null);

        assertEquals(404, response.getStatusCode().value());
        assertInstanceOf(StringNode.class, response.getBody());
        assertEquals("Unable to find job object for id 404", response.getBody().asText());
    }

    @Test
    void runJob_setupUnavailable_returns503() {
        when(jobExecutionService.enqueueJob(any(), any()))
                .thenThrow(new JobIntakeUnavailableException("Graders are still being prepared."));

        ResponseEntity<JsonNode> response = controller.runJob(1L, "\"submission.py\"", null, null);

        assertEquals(503, response.getStatusCode().value());
        assertEquals("Graders are still being prepared.", response.getBody().asText());
    }

    @Test
    void runJob_nonQueuedJob_returns400() {
        when(jobExecutionService.enqueueJob(any(), any()))
                .thenThrow(new IllegalArgumentException("Job 7 is not queued."));

        ResponseEntity<JsonNode> response = controller.runJob(7L, "\"submission.py\"", null, null);

        assertEquals(400, response.getStatusCode().value());
        assertEquals("Job 7 is not queued.", response.getBody().asText());
    }

    @Test
    void updateJob_success_returnsOkMessage() throws Exception {
        ResponseEntity<String> response = controller.updateJob(1L, objectMapper.createObjectNode());

        assertEquals(200, response.getStatusCode().value());
        assertEquals("Successfully updated job.", response.getBody());
    }

    @Test
    void updateJob_missingJob_returns404() throws Exception {
        doThrow(new JobNotFoundException("Unable to find existing job with id 19"))
                .when(jobExecutionService).updateJob(any(), any());

        ResponseEntity<String> response = controller.updateJob(19L, objectMapper.createObjectNode());

        assertEquals(404, response.getStatusCode().value());
        assertEquals("Unable to find existing job with id 19", response.getBody());
    }

    @Test
    void updateJob_invalidResult_returns500() throws Exception {
        doThrow(new IllegalArgumentException("Grader result is missing required field: status"))
                .when(jobExecutionService).updateJob(any(), any());

        ResponseEntity<String> response = controller.updateJob(20L, objectMapper.createObjectNode());

        assertEquals(500, response.getStatusCode().value());
        assertEquals("Failed to update job: Grader result is missing required field: status", response.getBody());
    }
}
