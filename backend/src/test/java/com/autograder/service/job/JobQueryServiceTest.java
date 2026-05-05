package com.autograder.service.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.autograder.model.Job;
import com.autograder.model.JobStatus;
import com.autograder.repository.JobRepository;

class JobQueryServiceTest {

    private final JobRepository jobRepository = Mockito.mock(JobRepository.class);
    private final JobQueryService service = new JobQueryService(jobRepository);

    @Test
    void getRecentJobs_returnsRepositoryResults() {
        Job job = new Job("submission.py", "fib", OffsetDateTime.now(), JobStatus.QUEUED);
        job.setSubmissionPath("db:2ee63863-c9ec-4a1f-8ce9-d4db05cc7a5c");
        job.setSubmissionId(4L);
        when(jobRepository.findAllOrderByCreatedAtDesc()).thenReturn(List.of(job));

        List<JobResponse> response = service.getRecentJobs();

        assertEquals(1, response.size());
        assertEquals("submission.py", response.get(0).originalFilename());
    }

    @Test
    void getJobById_missingJob_throwsNotFound() {
        when(jobRepository.findById(77L)).thenReturn(Optional.empty());

        JobNotFoundException exception = assertThrows(JobNotFoundException.class, () -> service.getJobById(77L));

        assertEquals("Unable to find job with id: 77", exception.getMessage());
    }

    @Test
    void downloadResults_existingResult_returnsPrettyJson() throws Exception {
        Job job = new Job("submission.py", "fib", OffsetDateTime.now(), JobStatus.SUCCEEDED);
        job.setResultJson("[{\"name\":\"case_1\",\"passed\":true}]");
        when(jobRepository.findById(12L)).thenReturn(Optional.of(job));

        DownloadedJobResult result = service.downloadResults(12L, true);

        assertTrue(result.body().contains("\"case_1\""));
        assertTrue(result.attachment());
    }

    @Test
    void downloadResults_missingResult_throwsUnavailable() {
        Job job = new Job("submission.py", "fib", OffsetDateTime.now(), JobStatus.QUEUED);
        when(jobRepository.findById(22L)).thenReturn(Optional.of(job));

        JobResultUnavailableException exception = assertThrows(
                JobResultUnavailableException.class,
                () -> service.downloadResults(22L, true)
        );

        assertEquals("Unable to get results for id: 22", exception.getMessage());
    }
}
