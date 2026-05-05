package com.autograder.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import com.autograder.model.Job;
import com.autograder.model.JobStatus;
import com.autograder.service.identity.RequestIdentity;
import com.autograder.service.identity.RequestIdentityProvider;
import com.autograder.service.job.DownloadedJobResult;
import com.autograder.service.job.JobNotFoundException;
import com.autograder.service.job.JobQueryService;
import com.autograder.service.job.JobResultUnavailableException;
import com.autograder.service.job.JobResponse;

class JobQueryControllerTest {

    private final JobQueryService jobQueryService = Mockito.mock(JobQueryService.class);
    private final JobQueryController controller = new JobQueryController(jobQueryService, new RequestIdentityProvider());

    @Test
    void getRecentJobs_returnsServiceResults() {
        Job first = new Job("a.py", "fib", OffsetDateTime.now(), JobStatus.QUEUED);
        JobResponse firstResponse = JobResponse.fromJob(first);
        when(jobQueryService.getRecentJobs(RequestIdentity.localAnonymous())).thenReturn(List.of(firstResponse));

        ResponseEntity<List<JobResponse>> response = controller.getRecentJobs(null, null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(List.of(firstResponse), response.getBody());
    }

    @Test
    void getJobById_existingJob_returnsJob() {
        Job job = new Job("submission.py", "fib", OffsetDateTime.now(), JobStatus.QUEUED);
        JobResponse jobResponse = JobResponse.fromJob(job);
        when(jobQueryService.getJobById(7L, RequestIdentity.localAnonymous())).thenReturn(jobResponse);

        ResponseEntity<?> response = controller.getJobById(7L, null, null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(jobResponse, response.getBody());
    }

    @Test
    void getJobById_missingJob_returns404() {
        when(jobQueryService.getJobById(77L, RequestIdentity.localAnonymous()))
                .thenThrow(new JobNotFoundException("Unable to find job with id: 77"));

        ResponseEntity<?> response = controller.getJobById(77L, null, null);

        assertEquals(404, response.getStatusCode().value());
        assertEquals("Unable to find job with id: 77", response.getBody());
    }

    @Test
    void downloadResults_attachment_setsJsonHeaders() throws Exception {
        when(jobQueryService.downloadResults(12L, true, RequestIdentity.localAnonymous()))
                .thenReturn(new DownloadedJobResult("[{\"name\":\"case_1\"}]", true));

        ResponseEntity<String> response = controller.downloadResults(12L, true, null, null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("application/json", response.getHeaders().getContentType().toString());
        assertEquals(
                "form-data; name=\"attachment\"; filename=\"results.json\"",
                response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)
        );
    }

    @Test
    void downloadResults_inline_omitsAttachmentHeader() throws Exception {
        when(jobQueryService.downloadResults(23L, false, RequestIdentity.localAnonymous()))
                .thenReturn(new DownloadedJobResult("[{\"name\":\"case_1\"}]", false));

        ResponseEntity<String> response = controller.downloadResults(23L, false, null, null);

        assertEquals(200, response.getStatusCode().value());
        assertNull(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
    }

    @Test
    void downloadResults_missingResult_returns404() throws Exception {
        when(jobQueryService.downloadResults(22L, true, RequestIdentity.localAnonymous()))
                .thenThrow(new JobResultUnavailableException("Unable to get results for id: 22"));

        ResponseEntity<String> response = controller.downloadResults(22L, true, null, null);

        assertEquals(404, response.getStatusCode().value());
        assertEquals("Unable to get results for id: 22", response.getBody());
    }
}
