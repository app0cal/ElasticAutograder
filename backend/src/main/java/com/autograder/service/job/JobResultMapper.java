package com.autograder.service.job;

import java.io.IOException;
import java.math.BigDecimal;

import org.springframework.stereotype.Service;

import com.autograder.model.FailureReason;
import com.autograder.model.Job;
import com.autograder.model.JobStatus;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Maps grader runtime JSON into the persisted Job summary fields.
 *
 * Keeping this outside controllers makes result parsing testable and reusable
 * for both synchronous runs and future queued workers.
 */
@Service
public class JobResultMapper {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Applies the grader result payload to a Job entity.
     *
     * @param job job row being updated
     * @param jobResults raw grader JSON returned by the runtime
     * @throws IOException if the per-test result JSON cannot be serialized
     */
    public void applyJobResults(Job job, JsonNode jobResults) throws IOException {
        if (jobResults == null || jobResults.get("status") == null) {
            throw new IllegalArgumentException("Grader result is missing required field: status");
        }

        JobStatus parsedStatus = parseJobStatus(jobResults.get("status").asText());
        job.setStatus(parsedStatus);

        if (jobResults.has("tests_passed")) {
            job.setTestsPassed(jobResults.get("tests_passed").asInt());
        }

        if (jobResults.has("tests_total")) {
            job.setTestsTotal(jobResults.get("tests_total").asInt());
        }

        if (jobResults.has("score") && !jobResults.get("score").isNull()) {
            job.setScore(jobResults.get("score").decimalValue());
        } else {
            job.setScore(BigDecimal.ZERO);
        }

        if (parsedStatus != JobStatus.FAILED) {
            job.setFailureReason(FailureReason.NONE);
            job.setFailureMessage(null);
        }

        if (parsedStatus == JobStatus.FAILED
                && jobResults.has("error_message")
                && !jobResults.get("error_message").isNull()) {
            job.setFailureMessage(jobResults.get("error_message").asText());

            boolean validationFailed = jobResults.has("validation_passed")
                    && !jobResults.get("validation_passed").isNull()
                    && !jobResults.get("validation_passed").asBoolean();

            if (job.getFailureReason() == null || job.getFailureReason() == FailureReason.NONE) {
                job.setFailureReason(validationFailed ? FailureReason.INVALID_UPLOAD : FailureReason.WRONG_ANSWER);
            }
        }

        if (jobResults.has("results")) {
            job.setResultJson(objectMapper.writeValueAsString(jobResults.get("results")));
        }
    }

    /**
     * Converts grader status text into the backend lifecycle enum.
     *
     * @param rawStatus status value from grader output
     * @return parsed backend status
     */
    public JobStatus parseJobStatus(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            throw new IllegalArgumentException("Job status is missing.");
        }

        return JobStatus.valueOf(rawStatus.trim().toUpperCase());
    }
}
