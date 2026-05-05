package com.autograder.service.grader;

import java.util.List;
import java.util.Optional;

import com.autograder.model.GraderDefinition;

/**
 * Lookup boundary for grader catalogs, allowing JSON or database-backed sources.
 */
public interface GraderCatalogProvider {

    List<GraderDefinition> findByInstitution(String institutionId);

    Optional<GraderDefinition> findByInstitutionAndKey(String institutionId, String key);
}
