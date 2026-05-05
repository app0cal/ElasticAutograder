package com.autograder.controller;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.autograder.service.identity.RequestIdentity;
import com.autograder.service.identity.RequestIdentityProvider;
import com.autograder.service.job.JobIntakeUnavailableException;
import com.autograder.service.job.JobSubmissionService;
import com.autograder.service.job.UploadJobResponse;

/**
 * REST controller for staging submissions and creating queued jobs.
 */
@CrossOrigin(origins = "http://localhost:5173/")
@RestController
@RequestMapping("/api")
public class JobSubmissionController {

    private final JobSubmissionService jobSubmissionService;
    private final RequestIdentityProvider requestIdentityProvider;

    public JobSubmissionController(
            JobSubmissionService jobSubmissionService,
            RequestIdentityProvider requestIdentityProvider
    ) {
        this.jobSubmissionService = jobSubmissionService;
        this.requestIdentityProvider = requestIdentityProvider;
    }

    /**
     * Uploads a submission file or batch archive and returns created job ids.
     *
     * @param file uploaded submission
     * @param graderType selected grader key
     * @param institutionHeader optional mock institution header
     * @param userHeader optional mock user header
     * @return upload result using the existing frontend response shape
     */
    @PostMapping("/jobs/upload")
    public ResponseEntity<Map<String, Object>> uploadFile(
            @RequestParam MultipartFile file,
            @RequestParam String graderType,
            @RequestHeader(value = "X-Mock-Institution", required = false) String institutionHeader,
            @RequestHeader(value = "X-Mock-User", required = false) String userHeader
    ) {
        try {
            RequestIdentity identity = requestIdentityProvider.resolve(institutionHeader, userHeader);
            UploadJobResponse response = jobSubmissionService.upload(file, graderType, identity);
            return ResponseEntity.ok(response.toResponseBody());
        } catch (JobIntakeUnavailableException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(errorBody(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(errorBody(e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(errorBody("Failed to save uploaded file."));
        }
    }

    private Map<String, Object> errorBody(String message) {
        return Map.of(
                "message", message,
                "jobs", List.of()
        );
    }
}
