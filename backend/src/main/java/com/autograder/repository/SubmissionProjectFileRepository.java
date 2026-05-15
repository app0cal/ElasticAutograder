package com.autograder.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.autograder.model.SubmissionProjectFile;

public interface SubmissionProjectFileRepository extends JpaRepository<SubmissionProjectFile, Long> {
    List<SubmissionProjectFile> findByProjectIdOrderByRelativePathAsc(Long projectId);

    void deleteByProjectId(Long projectId);
}
