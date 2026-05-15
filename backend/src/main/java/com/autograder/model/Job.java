package com.autograder.model;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.autograder.service.identity.RequestIdentity;

/**
 * JPA entity representing one submitted grading job.
 *
 * This model stores:
 * - basic submission metadata such as file name and grader type
 * - execution lifecycle state such as queued/running/succeeded/failed
 * - failure details for jobs that do not complete successfully
 * - grading summary data such as score, test counts, and result JSON
 * - Kubernetes job information when the submission is executed in-cluster
 */
@Entity
@Table(name = "jobs")
public class Job {

    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "grader_type", nullable = false)
    private String graderType;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    @Column(name = "submission_path")
    private String submissionPath;

    @Column(name = "submission_id")
    private Long submissionId;

    @Column(name = "submission_project_id")
    private Long submissionProjectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "submission_kind", nullable = false)
    private SubmissionKind submissionKind;

    @Column(name = "grader_image")
    private String graderImage;

    @Column(name = "institution_id", nullable = false)
    private String institutionId;

    @Column(name = "submitted_by", nullable = false)
    private String submittedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private JobStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_reason")
    private FailureReason failureReason;

    @Column(name = "failure_message", columnDefinition = "text")
    private String failureMessage;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Column(name = "queued_at")
    private OffsetDateTime queuedAt;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    @Column(name = "max_attempts", nullable = false)
    private Integer maxAttempts;

    @Column(name = "last_attempt_at")
    private OffsetDateTime lastAttemptAt;

    @Column(name = "queue_message_id")
    private String queueMessageId;

    @Column(name = "worker_id")
    private String workerId;

    @Column(name = "score")
    private BigDecimal score;

    @Column(name = "tests_passed")
    private Integer testsPassed;

    @Column(name = "tests_total")
    private Integer testsTotal;

    @Type(JsonBinaryType.class)
    @Column(name = "result_json", columnDefinition = "jsonb")
    private String resultJson;

    @Column(name = "k8s_job_name")
    private String k8sJobName;

    public Job() {
    }

    public Job(String originalFilename, String graderType, OffsetDateTime createdAt, JobStatus status) {
        this.originalFilename = originalFilename;
        this.graderType = graderType;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.status = status;
        this.failureReason = FailureReason.NONE;
        this.failureMessage = null;
        this.submissionKind = SubmissionKind.SINGLE_FILE;
        this.institutionId = RequestIdentity.DEFAULT_INSTITUTION;
        this.submittedBy = RequestIdentity.DEFAULT_USER;
        this.queuedAt = status == JobStatus.QUEUED ? createdAt : null;
        this.attemptCount = 0;
        this.maxAttempts = DEFAULT_MAX_ATTEMPTS;
    }

    public Long getId() {
        return id;
    }

    public String getGraderType() {
        return graderType;
    }

    public void setGraderType(String graderType) {
        this.graderType = graderType;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }

    public String getSubmissionPath() {
        return submissionPath;
    }

    public void setSubmissionPath(String submissionPath) {
        this.submissionPath = submissionPath;
    }

    public Long getSubmissionId() {
        return submissionId;
    }

    public void setSubmissionId(Long submissionId) {
        this.submissionId = submissionId;
    }

    public Long getSubmissionProjectId() {
        return submissionProjectId;
    }

    public void setSubmissionProjectId(Long submissionProjectId) {
        this.submissionProjectId = submissionProjectId;
    }

    public SubmissionKind getSubmissionKind() {
        return submissionKind;
    }

    public void setSubmissionKind(SubmissionKind submissionKind) {
        this.submissionKind = submissionKind;
    }

    public String getGraderImage() {
        return graderImage;
    }

    public void setGraderImage(String graderImage) {
        this.graderImage = graderImage;
    }

    public String getInstitutionId() {
        return institutionId;
    }

    public void setInstitutionId(String institutionId) {
        this.institutionId = institutionId;
    }

    public String getSubmittedBy() {
        return submittedBy;
    }

    public void setSubmittedBy(String submittedBy) {
        this.submittedBy = submittedBy;
    }

    public JobStatus getStatus() {
        return status;
    }

    public void setStatus(JobStatus status) {
        this.status = status;
    }

    public FailureReason getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(FailureReason failureReason) {
        this.failureReason = failureReason;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public void setFailureMessage(String failureMessage) {
        this.failureMessage = failureMessage;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(OffsetDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public OffsetDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(OffsetDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }

    public OffsetDateTime getQueuedAt() {
        return queuedAt;
    }

    public void setQueuedAt(OffsetDateTime queuedAt) {
        this.queuedAt = queuedAt;
    }

    public Integer getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(Integer attemptCount) {
        this.attemptCount = attemptCount;
    }

    public Integer getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(Integer maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public OffsetDateTime getLastAttemptAt() {
        return lastAttemptAt;
    }

    public void setLastAttemptAt(OffsetDateTime lastAttemptAt) {
        this.lastAttemptAt = lastAttemptAt;
    }

    public String getQueueMessageId() {
        return queueMessageId;
    }

    public void setQueueMessageId(String queueMessageId) {
        this.queueMessageId = queueMessageId;
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public BigDecimal getScore() {
        return score;
    }

    public void setScore(BigDecimal score) {
        this.score = score;
    }

    public Integer getTestsPassed() {
        return testsPassed;
    }

    public void setTestsPassed(Integer testsPassed) {
        this.testsPassed = testsPassed;
    }

    public Integer getTestsTotal() {
        return testsTotal;
    }

    public void setTestsTotal(Integer testsTotal) {
        this.testsTotal = testsTotal;
    }

    public String getResultJson() {
        return resultJson;
    }

    public void setResultJson(String resultJson) {
        this.resultJson = resultJson;
    }

    public String getK8sJobName() {
        return k8sJobName;
    }

    public void setK8sJobName(String k8sJobName) {
        this.k8sJobName = k8sJobName;
    }
}
