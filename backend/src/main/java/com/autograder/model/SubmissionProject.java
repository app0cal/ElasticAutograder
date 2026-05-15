package com.autograder.model;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * JPA entity for a zip archive stored as one multi-file project submission.
 */
@Entity
@Table(name = "submission_projects")
public class SubmissionProject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "storage_key", nullable = false, unique = true)
    private UUID storageKey;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    @Column(name = "institution_id", nullable = false)
    private String institutionId;

    @Column(name = "submitted_by", nullable = false)
    private String submittedBy;

    @Column(name = "total_size_bytes", nullable = false)
    private Long totalSizeBytes;

    @Column(name = "file_count", nullable = false)
    private Integer fileCount;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public SubmissionProject() {
    }

    public SubmissionProject(
            UUID storageKey,
            String originalFilename,
            String institutionId,
            String submittedBy,
            Long totalSizeBytes,
            Integer fileCount
    ) {
        this.storageKey = storageKey;
        this.originalFilename = originalFilename;
        this.institutionId = institutionId;
        this.submittedBy = submittedBy;
        this.totalSizeBytes = totalSizeBytes;
        this.fileCount = fileCount;
    }

    public Long getId() {
        return id;
    }

    public UUID getStorageKey() {
        return storageKey;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getInstitutionId() {
        return institutionId;
    }

    public String getSubmittedBy() {
        return submittedBy;
    }

    public Long getTotalSizeBytes() {
        return totalSizeBytes;
    }

    public Integer getFileCount() {
        return fileCount;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
