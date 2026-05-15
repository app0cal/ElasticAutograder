package com.autograder.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import com.autograder.model.GraderDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Loads definition of each grader from the grader.json config file, 
 * applies default settings where applicable and validates each grader
*/
@Component
public class GraderConfigLoader {

    // default path supports running a release jar from the repository/release root
    private static final Path DEFAULT_CONFIG_PATH = Path.of("config", "graders.json");

    // platform defaults for graders unless overridden in config
    private static final int DEFAULT_TIMEOUT_SECONDS = 10; // 10 originally
    private static final int DEFAULT_CPU_REQUEST_MILLI = 100; // 100 originally
    private static final int DEFAULT_CPU_LIMIT_MILLI = 500; // 500 originally
    private static final int DEFAULT_MEMORY_REQUEST_MB = 128; // 128 originally
    private static final int DEFAULT_MEMORY_LIMIT_MB = 512; // 512 originally
    private static final String DEFAULT_UPLOAD_MODE = "batch_zip";

    // Jackson mapper to turn grader.json into java objects we can read
    private final ObjectMapper objectMapper;
    private final Path configPath;

    /**
     * Constructs the config loader with the shared ObjectMapper bean.
     *
     * @param objectMapper mapper used to parse JSON config into GraderConfig
     */
    @Autowired
    public GraderConfigLoader(
            ObjectMapper objectMapper,
            @Value("${graders.config-path:config/graders.json}") String configPath) {
        this.objectMapper = objectMapper;
        this.configPath = Path.of(configPath);
    }

    public GraderConfigLoader(ObjectMapper objectMapper) {
        this(objectMapper, DEFAULT_CONFIG_PATH.toString());
    }

    /**
     * Loads graders from the default config path.
     *
     * @return fully processed grader definitions from graders.json
     */
    public List<GraderDefinition> loadGraders() {
        return loadGraders(configPath);
    }

    /**
     * Loads graders from the provided config path, applies default values,
     * validates each grader entry, and returns the final list.
     *
     * @param configPath path to graders.json
     * @return list of grader definitions ready for use by the backend
     * @throws IllegalStateException
     **/
    public List<GraderDefinition> loadGraders(Path configPath) {
        // if no files exist in the path return exception
        if (!Files.exists(configPath)) {
            throw new IllegalStateException("Grader config file not found: " + configPath.toAbsolutePath());
        }

        try {
            // parse the json file and wrap it into the GraderConfig file
            GraderConfig graderConfig = objectMapper.readValue(configPath.toFile(), GraderConfig.class);

            // reject missing/improperly created graders
            if (graderConfig == null || graderConfig.getGraders() == null) {
                throw new IllegalStateException("Invalid grader config: missing 'graders' list.");
            }

            // applies default values 
            applyDefaults(graderConfig.getGraders());

            // validate grader enteries (error handling n other stuff)
            validateGraders(graderConfig.getGraders());

            return graderConfig.getGraders();

        } catch (IOException e) {
            throw new IllegalStateException("Failed to read grader config from: " + configPath.toAbsolutePath(), e);
        }
    }

    /**
     * Loads graders from the provided config path and applies default values where applicable
     *
     * @param graders list of graders loaded from config file
     **/
    private void applyDefaults(List<GraderDefinition> graders) {
        for (GraderDefinition grader : graders) {
            applyDefaults(grader);
        }
    }

    /**
     * Applies platform defaults to a grader when optional
     * timeout/resource settings weren't set
     *
     * @param grader grader definition to fill with defaults
     */
    private void applyDefaults(GraderDefinition grader) {
        if (grader.getTimeoutSeconds() == null) {
            grader.setTimeoutSeconds(DEFAULT_TIMEOUT_SECONDS);
        }

        if (grader.getCpuRequestMilli() == null) {
            grader.setCpuRequestMilli(DEFAULT_CPU_REQUEST_MILLI);
        }

        if (grader.getCpuLimitMilli() == null) {
            grader.setCpuLimitMilli(DEFAULT_CPU_LIMIT_MILLI);
        }

        if (grader.getMemoryRequestMb() == null) {
            grader.setMemoryRequestMb(DEFAULT_MEMORY_REQUEST_MB);
        }

        if (grader.getMemoryLimitMb() == null) {
            grader.setMemoryLimitMb(DEFAULT_MEMORY_LIMIT_MB);
        }

        if (grader.getGraderFolder() == null || grader.getGraderFolder().isBlank()) {
            grader.setGraderFolder(grader.getKey());
        } else {
            grader.setGraderFolder(grader.getGraderFolder().trim());
        }

        if (grader.getLanguage() != null) {
            grader.setLanguage(grader.getLanguage().trim());
        }

        if (grader.getUploadMode() == null || grader.getUploadMode().isBlank()) {
            grader.setUploadMode(DEFAULT_UPLOAD_MODE);
        } else {
            grader.setUploadMode(grader.getUploadMode().trim().toLowerCase());
        }
    }

    /**
     * Validates the full list of graders and rejects invalid config early.
     * Also checks for duplicate grader keys
     *
     * @param graders list of grader definitions to validate
     * @throws IllegalStateException if the list is empty or contains invalid entries
     */
    private void validateGraders(List<GraderDefinition> graders) {
        if (graders.isEmpty()) {
            throw new IllegalStateException("Grader config is empty. At least one grader must be defined.");
        }

        HashSet<String> seenKeys = new HashSet<>();

        for (GraderDefinition grader : graders) {
          applyInstitutionDefault(grader);
          String scopedKey = grader.getInstitutionId() + ":" + grader.getKey();
          if (!seenKeys.add(scopedKey)) {
            throw new IllegalStateException("Duplicate grader key found for institution: " + grader.getInstitutionId());
          }
          validateGrader(grader);
        }
    }

    private void applyInstitutionDefault(GraderDefinition grader) {
        if (grader.getInstitutionId() == null || grader.getInstitutionId().isBlank()) {
            grader.setInstitutionId("local");
        } else {
            grader.setInstitutionId(grader.getInstitutionId().trim());
        }
    }

    /**
     * Validates a grader
     * This ensures required metadata exists and that resource settings aren't contradictory or error prone
     *
     * @param grader grader definition to validate
     * @throws IllegalStateException if any required field is missing or invalid
     */
    private void validateGrader(GraderDefinition grader){      
      if (grader.getKey() == null || grader.getKey().isBlank()) {
            throw new IllegalStateException("Each grader must have a non-empty key.");
        }

      if (grader.getInstitutionId() == null || grader.getInstitutionId().isBlank()) {
          throw new IllegalStateException("Grader '" + grader.getKey() + "' is missing an institutionId.");
      }

      if (grader.getLabel() == null || grader.getLabel().isBlank()) {
          throw new IllegalStateException("Grader '" + grader.getKey() + "' is missing a label.");
      }

      if (grader.getImageName() == null || grader.getImageName().isBlank()) {
          throw new IllegalStateException("Grader '" + grader.getKey() + "' is missing an imageName.");
      }

      if (grader.getLanguage() != null && grader.getLanguage().isBlank()) {
          throw new IllegalStateException("Grader '" + grader.getKey() + "' has invalid language.");
      }

      if (!isValidUploadMode(grader.getUploadMode())) {
          throw new IllegalStateException("Grader '" + grader.getKey() + "' has invalid uploadMode.");
      }

      if (grader.getGraderFolder() == null || grader.getGraderFolder().isBlank()) {
          throw new IllegalStateException("Grader '" + grader.getKey() + "' is missing a graderFolder.");
      }

      if (grader.getManifestPath() == null || grader.getManifestPath().isBlank()) {
          throw new IllegalStateException("Grader '" + grader.getKey() + "' is missing a manifestPath.");
      }

      if (grader.getSummary() == null || grader.getSummary().isBlank()) {
          throw new IllegalStateException("Grader '" + grader.getKey() + "' is missing a summary.");
      }

      if (grader.getDetails() != null) {
          for (String detail : grader.getDetails()) {
              if (detail == null || detail.isBlank()) {
                  throw new IllegalStateException("Grader '" + grader.getKey() + "' has an invalid details entry.");
              }
          }
      }

      if (grader.getTimeoutSeconds() == null || grader.getTimeoutSeconds() <= 0) {
          throw new IllegalStateException("Grader '" + grader.getKey() + "' has invalid timeoutSeconds.");
      }

      if (grader.getCpuRequestMilli() != null && grader.getCpuRequestMilli() <= 0) {
          throw new IllegalStateException("Grader '" + grader.getKey() + "' has invalid cpuRequestMilli.");
      }

      if (grader.getCpuLimitMilli() != null && grader.getCpuLimitMilli() <= 0) {
          throw new IllegalStateException("Grader '" + grader.getKey() + "' has invalid cpuLimitMilli.");
      }

      if (grader.getMemoryRequestMb() != null && grader.getMemoryRequestMb() <= 0) {
          throw new IllegalStateException("Grader '" + grader.getKey() + "' has invalid memoryRequestMb.");
      }

      if (grader.getMemoryLimitMb() != null && grader.getMemoryLimitMb() <= 0) {
          throw new IllegalStateException("Grader '" + grader.getKey() + "' has invalid memoryLimitMb.");
      }

      if (grader.getCpuRequestMilli() != null && grader.getCpuLimitMilli() != null
              && grader.getCpuRequestMilli() > grader.getCpuLimitMilli()) {
          throw new IllegalStateException(
                  "Grader '" + grader.getKey() + "' has cpuRequestMilli greater than cpuLimitMilli."
          );
      }

      if (grader.getMemoryRequestMb() != null && grader.getMemoryLimitMb() != null
              && grader.getMemoryRequestMb() > grader.getMemoryLimitMb()) {
          throw new IllegalStateException(
                  "Grader '" + grader.getKey() + "' has memoryRequestMb greater than memoryLimitMb."
          );
      }
    }

    private boolean isValidUploadMode(String uploadMode) {
        return "single_file".equals(uploadMode)
                || "batch_zip".equals(uploadMode)
                || "project_zip".equals(uploadMode);
    }
}
