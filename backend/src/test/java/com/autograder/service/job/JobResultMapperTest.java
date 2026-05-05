package com.autograder.service.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

import com.autograder.model.FailureReason;
import com.autograder.model.Job;
import com.autograder.model.JobStatus;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class JobResultMapperTest {

    private final JobResultMapper mapper = new JobResultMapper();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void applyJobResults_partialResult_persistsSummaryAndResults() throws Exception {
        Job job = job();
        ObjectNode result = baseResult("PARTIAL");
        result.put("tests_passed", 1);
        result.put("tests_total", 2);
        result.put("score", new BigDecimal("50.0"));
        result.putArray("results").addObject().put("name", "case_1").put("passed", false);

        mapper.applyJobResults(job, result);

        assertEquals(JobStatus.PARTIAL, job.getStatus());
        assertEquals(1, job.getTestsPassed());
        assertEquals(2, job.getTestsTotal());
        assertEquals(0, new BigDecimal("50.0").compareTo(job.getScore()));
        assertEquals(FailureReason.NONE, job.getFailureReason());
        assertNull(job.getFailureMessage());
        assertTrue(job.getResultJson().contains("case_1"));
    }

    @Test
    void applyJobResults_failedValidation_setsInvalidUploadReason() throws Exception {
        Job job = job();
        ObjectNode result = baseResult("FAILED");
        result.put("validation_passed", false);
        result.put("error_message", "missing callable");
        result.putArray("results");

        mapper.applyJobResults(job, result);

        assertEquals(JobStatus.FAILED, job.getStatus());
        assertEquals(FailureReason.INVALID_UPLOAD, job.getFailureReason());
        assertEquals("missing callable", job.getFailureMessage());
    }

    @Test
    void applyJobResults_failedAnswer_setsWrongAnswerReason() throws Exception {
        Job job = job();
        ObjectNode result = baseResult("FAILED");
        result.put("validation_passed", true);
        result.put("error_message", "No test cases passed.");
        ArrayNode results = result.putArray("results");
        results.addObject().put("name", "case_1").put("passed", false);

        mapper.applyJobResults(job, result);

        assertEquals(JobStatus.FAILED, job.getStatus());
        assertEquals(FailureReason.WRONG_ANSWER, job.getFailureReason());
        assertEquals("No test cases passed.", job.getFailureMessage());
    }

    @Test
    void applyJobResults_missingStatus_rejectsPayload() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> mapper.applyJobResults(job(), objectMapper.createObjectNode())
        );

        assertEquals("Grader result is missing required field: status", exception.getMessage());
    }

    private ObjectNode baseResult(String status) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("status", status);
        return result;
    }

    private Job job() {
        return new Job("submission.py", "fib", OffsetDateTime.now(), JobStatus.RUNNING);
    }
}
