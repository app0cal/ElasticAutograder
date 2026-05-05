package com.autograder.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.autograder.service.identity.RequestIdentity;
import com.autograder.service.identity.RequestIdentityProvider;
import com.autograder.service.job.JobExecutionService;
import com.autograder.service.job.JobIntakeUnavailableException;
import com.autograder.service.job.JobNotFoundException;
import com.autograder.service.job.RunJobResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.StringNode;

/**
 * REST controller for running jobs and accepting optional result callbacks.
 */
@CrossOrigin(origins = "http://localhost:5173/")
@RestController
@RequestMapping("/api")
public class JobExecutionController {

    private final JobExecutionService jobExecutionService;
    private final RequestIdentityProvider requestIdentityProvider;

    public JobExecutionController(
            JobExecutionService jobExecutionService,
            RequestIdentityProvider requestIdentityProvider
    ) {
        this.jobExecutionService = jobExecutionService;
        this.requestIdentityProvider = requestIdentityProvider;
    }

    /**
     * Runs a staged submission through the configured job dispatcher.
     *
     * @param id job id to run
     * @param fileName legacy request body containing the staged file name
     * @param institutionHeader optional mock institution header
     * @param userHeader optional mock user header
     * @return grader result JSON or an error message node
     */
    @PostMapping("/jobs/run/{id}")
    public ResponseEntity<JsonNode> runJob(
            @PathVariable Long id,
            @RequestBody(required = false) String fileName,
            @RequestHeader(value = "X-Mock-Institution", required = false) String institutionHeader,
            @RequestHeader(value = "X-Mock-User", required = false) String userHeader
    ) {
        try {
            RequestIdentity identity = requestIdentityProvider.resolve(institutionHeader, userHeader);
            RunJobResult result = jobExecutionService.runJob(id, fileName, identity);
            return ResponseEntity.status(result.status()).body(result.body());
        } catch (JobIntakeUnavailableException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new StringNode(e.getMessage()));
        } catch (JobNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new StringNode(e.getMessage()));
        }
    }

    /**
     * Applies pushed result JSON to an existing job.
     *
     * @param id job id to update
     * @param jobResults grader result payload
     * @return simple success or error message
     */
    @PostMapping("/jobs/{id}/callback")
    public ResponseEntity<String> updateJob(@PathVariable Long id, @RequestBody JsonNode jobResults) {
        try {
            jobExecutionService.updateJob(id, jobResults);
            return ResponseEntity.ok("Successfully updated job.");
        } catch (JobNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to update job: " + e.getMessage());
        }
    }
}
