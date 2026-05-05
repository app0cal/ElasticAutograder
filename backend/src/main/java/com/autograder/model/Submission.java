package com.autograder.model;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.autograder.service.identity.RequestIdentity;

/**
 * JPA entity for uploaded submission source stored in shared durable storage.
 */
@Entity
@Table(name = "submissions")
public class Submission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "storage_key", nullable = false, unique = true)
    private UUID storageKey;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "institution_id", nullable = false)
    private String institutionId;

    @Column(name = "submitted_by", nullable = false)
    private String submittedBy;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public Submission() {
    }

    public Submission(UUID storageKey, String originalFilename, String content, String contentType, Long sizeBytes) {
        this(storageKey, originalFilename, content, contentType, sizeBytes,
                RequestIdentity.DEFAULT_INSTITUTION, RequestIdentity.DEFAULT_USER);
    }

    public Submission(
            UUID storageKey,
            String originalFilename,
            String content,
            String contentType,
            Long sizeBytes,
            String institutionId,
            String submittedBy
    ) {
        this.storageKey = storageKey;
        this.originalFilename = originalFilename;
        this.content = content;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.institutionId = institutionId;
        this.submittedBy = submittedBy;
    }

    public Long getId() {
        return id;
    }

    public UUID getStorageKey() {
        return storageKey;
    }

    public void setStorageKey(UUID storageKey) {
        this.storageKey = storageKey;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
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

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
