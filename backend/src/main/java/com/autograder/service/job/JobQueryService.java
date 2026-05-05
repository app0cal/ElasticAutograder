package com.autograder.service.job;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.autograder.model.Job;
import com.autograder.repository.JobRepository;
import com.autograder.service.identity.RequestIdentity;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Read-side service for job history, job details, and stored result downloads.
 */
@Service
public class JobQueryService {

    private final JobRepository jobRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JobQueryService(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    public List<JobResponse> getRecentJobs() {
        return getRecentJobs(RequestIdentity.localAnonymous());
    }

    public List<JobResponse> getRecentJobs(RequestIdentity identity) {
        return jobRepository.findAllByInstitutionIdOrderByCreatedAtDesc(identity.institution()).stream()
                .map(JobResponse::fromJob)
                .toList();
    }

    public JobResponse getJobById(Long id) {
        return getJobById(id, RequestIdentity.localAnonymous());
    }

    public JobResponse getJobById(Long id, RequestIdentity identity) {
        return JobResponse.fromJob(getJobEntityById(id, identity));
    }

    private Job getJobEntityById(Long id) {
        return getJobEntityById(id, RequestIdentity.localAnonymous());
    }

    private Job getJobEntityById(Long id, RequestIdentity identity) {
        Optional<Job> jobEntity = jobRepository.findByIdAndInstitutionId(id, identity.institution());
        if (jobEntity.isEmpty()) {
            throw new JobNotFoundException("Unable to find job with id: " + id);
        }

        return jobEntity.get();
    }

    public DownloadedJobResult downloadResults(Long id, boolean fromTable, RequestIdentity identity) throws IOException {
        Job job = getJobEntityById(id, identity);
        if (job.getResultJson() == null) {
            throw new JobResultUnavailableException("Unable to get results for id: " + id);
        }

        JsonNode resultJson = objectMapper.readTree(job.getResultJson());
        String prettyJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(resultJson);
        return new DownloadedJobResult(prettyJson, fromTable);
    }

    /**
     * Loads and pretty-prints stored result JSON for download or inline viewing.
     *
     * @param id job id whose results should be returned
     * @param fromTable whether the caller expects an attachment header
     * @return prepared JSON response body and attachment flag
     */
    public DownloadedJobResult downloadResults(Long id, boolean fromTable) throws IOException {
        return downloadResults(id, fromTable, RequestIdentity.localAnonymous());
    }
}
