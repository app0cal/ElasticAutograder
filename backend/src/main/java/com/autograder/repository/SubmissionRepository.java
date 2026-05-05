package com.autograder.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.autograder.model.Submission;

/**
 * Repository for durable uploaded submission content and lookup keys.
 */
public interface SubmissionRepository extends JpaRepository<Submission, Long> {

    Optional<Submission> findByStorageKey(UUID storageKey);
}
