package com.autograder.model;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * JPA entity for one file inside a project submission archive.
 */
@Entity
@Table(name = "submission_project_files")
public class SubmissionProjectFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private SubmissionProject project;

    @Column(name = "relative_path", nullable = false)
    private String relativePath;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public SubmissionProjectFile() {
    }

    public SubmissionProjectFile(
            SubmissionProject project,
            String relativePath,
            String content,
            String contentType,
            Long sizeBytes
    ) {
        this.project = project;
        this.relativePath = relativePath;
        this.content = content;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
    }

    public Long getId() {
        return id;
    }

    public SubmissionProject getProject() {
        return project;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public String getContent() {
        return content;
    }

    public String getContentType() {
        return contentType;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
