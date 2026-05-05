package com.autograder.controller;

import java.io.IOException;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.autograder.service.identity.RequestIdentity;
import com.autograder.service.identity.RequestIdentityProvider;
import com.autograder.service.job.DownloadedJobResult;
import com.autograder.service.job.JobNotFoundException;
import com.autograder.service.job.JobQueryService;
import com.autograder.service.job.JobResultUnavailableException;
import com.autograder.service.job.JobResponse;

/**
 * REST controller for reading job history, details, and stored result JSON.
 */
@CrossOrigin(origins = "http://localhost:5173/")
@RestController
@RequestMapping("/api")
public class JobQueryController {

    private final JobQueryService jobQueryService;
    private final RequestIdentityProvider requestIdentityProvider;

    public JobQueryController(
            JobQueryService jobQueryService,
            RequestIdentityProvider requestIdentityProvider
    ) {
        this.jobQueryService = jobQueryService;
        this.requestIdentityProvider = requestIdentityProvider;
    }

    @GetMapping("/jobs/recent")
    public ResponseEntity<List<JobResponse>> getRecentJobs(
            @RequestHeader(value = "X-Mock-Institution", required = false) String institutionHeader,
            @RequestHeader(value = "X-Mock-User", required = false) String userHeader
    ) {
        RequestIdentity identity = requestIdentityProvider.resolve(institutionHeader, userHeader);
        return ResponseEntity.ok(jobQueryService.getRecentJobs(identity));
    }

    @GetMapping("/jobs/{id}")
    public ResponseEntity<?> getJobById(
            @PathVariable Long id,
            @RequestHeader(value = "X-Mock-Institution", required = false) String institutionHeader,
            @RequestHeader(value = "X-Mock-User", required = false) String userHeader
    ) {
        try {
            RequestIdentity identity = requestIdentityProvider.resolve(institutionHeader, userHeader);
            return ResponseEntity.ok(jobQueryService.getJobById(id, identity));
        } catch (JobNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        }
    }

    /**
     * Returns stored result JSON as either an attachment or inline response.
     *
     * @param id job id whose results should be returned
     * @param fromTable whether the response should include an attachment header
     * @return pretty-printed JSON result body or a not-found error
     */
    @GetMapping("/jobs/result/{id}")
    public ResponseEntity<String> downloadResults(
            @PathVariable Long id,
            @RequestParam(defaultValue = "true") boolean fromTable,
            @RequestHeader(value = "X-Mock-Institution", required = false) String institutionHeader,
            @RequestHeader(value = "X-Mock-User", required = false) String userHeader
    ) throws IOException {
        try {
            RequestIdentity identity = requestIdentityProvider.resolve(institutionHeader, userHeader);
            DownloadedJobResult result = jobQueryService.downloadResults(id, fromTable, identity);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (result.attachment()) {
                headers.setContentDispositionFormData("attachment", "results.json");
            }
            return new ResponseEntity<>(result.body(), headers, HttpStatus.OK);
        } catch (JobNotFoundException | JobResultUnavailableException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        }
    }
}
