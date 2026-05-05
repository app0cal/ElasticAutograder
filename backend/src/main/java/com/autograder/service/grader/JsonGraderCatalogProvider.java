package com.autograder.service.grader;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.autograder.config.GraderConfigLoader;
import com.autograder.model.GraderDefinition;

/**
 * JSON-backed grader catalog used until grader definitions move into Postgres.
 */
@Service
public class JsonGraderCatalogProvider implements GraderCatalogProvider {

    private final Map<String, List<GraderDefinition>> gradersByInstitution;
    private final Map<InstitutionGraderKey, GraderDefinition> gradersByInstitutionAndKey;

    @Autowired
    public JsonGraderCatalogProvider(GraderConfigLoader graderConfigLoader) {
        this(graderConfigLoader.loadGraders());
    }

    public JsonGraderCatalogProvider(List<GraderDefinition> graderDefinitions) {
        graderDefinitions.forEach(this::applyInstitutionDefault);
        this.gradersByInstitution = graderDefinitions.stream()
                .collect(Collectors.groupingBy(GraderDefinition::getInstitutionId));
        this.gradersByInstitutionAndKey = graderDefinitions.stream()
                .collect(Collectors.toMap(
                        grader -> new InstitutionGraderKey(grader.getInstitutionId(), grader.getKey()),
                        Function.identity()
                ));
    }

    @Override
    public List<GraderDefinition> findByInstitution(String institutionId) {
        return List.copyOf(gradersByInstitution.getOrDefault(institutionId, List.of()));
    }

    @Override
    public Optional<GraderDefinition> findByInstitutionAndKey(String institutionId, String key) {
        return Optional.ofNullable(gradersByInstitutionAndKey.get(new InstitutionGraderKey(institutionId, key)));
    }

    private record InstitutionGraderKey(String institutionId, String key) {
    }

    private void applyInstitutionDefault(GraderDefinition grader) {
        if (grader.getInstitutionId() == null || grader.getInstitutionId().isBlank()) {
            grader.setInstitutionId("local");
        } else {
            grader.setInstitutionId(grader.getInstitutionId().trim());
        }
    }
}
