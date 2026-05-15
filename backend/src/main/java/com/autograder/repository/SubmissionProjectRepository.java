package com.autograder.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.autograder.model.SubmissionProject;

public interface SubmissionProjectRepository extends JpaRepository<SubmissionProject, Long> {
    Optional<SubmissionProject> findByStorageKey(UUID storageKey);
}
