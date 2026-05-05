package com.autograder.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import com.autograder.service.identity.RequestIdentityProvider;
import com.autograder.service.job.JobIntakeUnavailableException;
import com.autograder.service.job.JobSubmissionService;
import com.autograder.service.job.UploadJobResponse;
import com.autograder.service.job.UploadedJobSummary;

class JobSubmissionControllerTest {

    private final JobSubmissionService jobSubmissionService = Mockito.mock(JobSubmissionService.class);
    private final JobSubmissionController controller = new JobSubmissionController(
            jobSubmissionService,
            new RequestIdentityProvider()
    );

    @Test
    void uploadFile_validRequest_returnsUploadResponse() throws Exception {
        MockMultipartFile file = file();
        when(jobSubmissionService.upload(any(), any(), any()))
                .thenReturn(new UploadJobResponse("Successfully queued file.", List.of(
                        new UploadedJobSummary(1L, "submission.py")
                )));

        ResponseEntity<Map<String, Object>> response = controller.uploadFile(file, "fib", null, null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("Successfully queued file.", response.getBody().get("message"));
        assertEquals(1, castJobs(response.getBody().get("jobs")).size());
    }

    @Test
    void uploadFile_validationError_returns400() throws Exception {
        when(jobSubmissionService.upload(any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Invalid file name."));

        ResponseEntity<Map<String, Object>> response = controller.uploadFile(file(), "fib", null, null);

        assertEquals(400, response.getStatusCode().value());
        assertEquals("Invalid file name.", response.getBody().get("message"));
        assertEquals(List.of(), response.getBody().get("jobs"));
    }

    @Test
    void uploadFile_storageFailure_returns500() throws Exception {
        when(jobSubmissionService.upload(any(), any(), any()))
                .thenThrow(new IOException("disk full"));

        ResponseEntity<Map<String, Object>> response = controller.uploadFile(file(), "fib", null, null);

        assertEquals(500, response.getStatusCode().value());
        assertEquals("Failed to save uploaded file.", response.getBody().get("message"));
        assertEquals(List.of(), response.getBody().get("jobs"));
    }

    @Test
    void uploadFile_setupUnavailable_returns503() throws Exception {
        when(jobSubmissionService.upload(any(), any(), any()))
                .thenThrow(new JobIntakeUnavailableException("Graders are still being prepared."));

        ResponseEntity<Map<String, Object>> response = controller.uploadFile(file(), "fib", null, null);

        assertEquals(503, response.getStatusCode().value());
        assertEquals("Graders are still being prepared.", response.getBody().get("message"));
        assertEquals(List.of(), response.getBody().get("jobs"));
    }

    private MockMultipartFile file() {
        return new MockMultipartFile("file", "submission.py", "text/plain", "print('hello')".getBytes());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castJobs(Object jobs) {
        return (List<Map<String, Object>>) jobs;
    }
}
