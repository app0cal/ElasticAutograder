package com.autograder.config;

import com.autograder.model.GraderDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GraderConfigLoaderTest {

    @TempDir
    Path tempDir;

    private GraderConfigLoader createLoader() {
        return new GraderConfigLoader(new ObjectMapper());
    }

    /**
     * Verifies that the no-arg load path can be configured for jar releases.
     * Expected behavior: loadGraders() reads from the constructor-provided config path.
     */
    @Test
    void loadGraders_configuredDefaultPath_returnsGraders() throws Exception {
        Path configFile = tempDir.resolve("release-config").resolve("graders.json");
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, """
                {
                  "graders": [
                    {
                      "key": "fib",
                      "label": "Fibonacci",
                      "imageName": "ea-grader-fib:v1",
                      "manifestPath": "/app/grader/manifest.json",
                      "summary": "Return the nth Fibonacci number."
                    }
                  ]
                }
                """);

        GraderConfigLoader loader = new GraderConfigLoader(new ObjectMapper(), configFile.toString());

        List<GraderDefinition> graders = loader.loadGraders();

        assertEquals(1, graders.size());
        assertEquals("fib", graders.get(0).getKey());
    }

    /**
     * Verifies that a complete grader config file loads every grader definition.
     * Expected behavior: all configured metadata and resource values are preserved.
     */
    @Test
    void loadGraders_validConfig_returnsGraders() throws Exception {
        Path configFile = tempDir.resolve("graders.json");

        String json = """
                {
                  "graders": [
                    {
                      "key": "fib",
                      "label": "Fibonacci",
                      "imageName": "ea-grader-fibbonaci:v1",
                      "language": "python",
                      "manifestPath": "/app/grader/manifest.json",
                      "summary": "Classic dynamic programming problem.",
                      "details": [
                        "Return the nth Fibonacci number.",
                        "Assume the sequence starts at 0 and 1."
                      ],
                      "timeoutSeconds": 10,
                      "cpuRequestMilli": 100,
                      "cpuLimitMilli": 500,
                      "memoryRequestMb": 128,
                      "memoryLimitMb": 512
                    },
                    {
                      "key": "twosum",
                      "label": "Two Sum",
                      "imageName": "ea-grader-twosum:v1",
                      "manifestPath": "/app/grader/manifest.json",
                      "summary": "Array and hash map problem.",
                      "details": [
                        "Return the indices of the two numbers that add up to the target."
                      ],
                      "timeoutSeconds": 10,
                      "cpuRequestMilli": 100,
                      "cpuLimitMilli": 500,
                      "memoryRequestMb": 128,
                      "memoryLimitMb": 512
                    }
                  ]
                }
                """;

        Files.writeString(configFile, json);

        GraderConfigLoader loader = createLoader();
        List<GraderDefinition> graders = loader.loadGraders(configFile);

        assertEquals(2, graders.size());

        GraderDefinition fib = graders.get(0);
        assertEquals("fib", fib.getKey());
        assertEquals("Fibonacci", fib.getLabel());
        assertEquals("ea-grader-fibbonaci:v1", fib.getImageName());
        assertEquals("python", fib.getLanguage());
        assertEquals("/app/grader/manifest.json", fib.getManifestPath());
        assertEquals("Classic dynamic programming problem.", fib.getSummary());
        assertEquals(2, fib.getDetails().size());
        assertEquals(10, fib.getTimeoutSeconds());
        assertEquals(100, fib.getCpuRequestMilli());
        assertEquals(500, fib.getCpuLimitMilli());
        assertEquals(128, fib.getMemoryRequestMb());
        assertEquals(512, fib.getMemoryLimitMb());
    }

    /**
     * Verifies that loading from a missing config file fails early.
     * Expected behavior: the loader throws an IllegalStateException with a clear missing-file message.
     */
    @Test
    void loadGraders_missingFile_throwsException() {
        Path missingPath = tempDir.resolve("does-not-exist.json");

        GraderConfigLoader loader = createLoader();

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> loader.loadGraders(missingPath)
        );

        assertTrue(ex.getMessage().contains("Grader config file not found"));
    }

    /**
     * Verifies that duplicate grader keys are rejected before registration.
     * Expected behavior: the loader throws an IllegalStateException instead of returning ambiguous graders.
     */
    @Test
    void loadGraders_duplicateKeys_throwsException() throws Exception {
        Path configFile = tempDir.resolve("graders.json");

        String json = """
        {
          "graders": [
            {
              "key": "fib",
              "label": "Fibonacci",
              "imageName": "ea-grader-fibbonaci:v1",
              "manifestPath": "/app/grader/manifest.json",
              "summary": "Dynamic programming warm-up.",
              "details": ["Return the nth Fibonacci number."],
              "timeoutSeconds": 10,
              "cpuRequestMilli": 100,
              "cpuLimitMilli": 500,
              "memoryRequestMb": 128,
              "memoryLimitMb": 512
            },
            {
              "key": "fib",
              "label": "Duplicate Fibonacci",
              "imageName": "ea-grader-fibbonaci:v2",
              "manifestPath": "/app/grader/manifest.json",
              "summary": "Duplicate key entry.",
              "details": ["Used to verify duplicate validation."],
              "timeoutSeconds": 10,
              "cpuRequestMilli": 100,
              "cpuLimitMilli": 500,
              "memoryRequestMb": 128,
              "memoryLimitMb": 512
            }
          ]
        }
        """;

        Files.writeString(configFile, json);

        GraderConfigLoader loader = createLoader();

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> loader.loadGraders(configFile)
        );

        assertTrue(ex.getMessage().contains("Duplicate grader key found"));
    }

    @Test
    void loadGraders_sameKeyDifferentInstitutions_returnsGraders() throws Exception {
        Path configFile = tempDir.resolve("graders.json");

        String json = """
        {
          "graders": [
            {
              "institutionId": "university-a",
              "key": "fib",
              "label": "Fibonacci A",
              "imageName": "ea-grader-fib-a:v1",
              "manifestPath": "/app/grader/manifest.json",
              "summary": "Institution A grader.",
              "details": ["Return the nth Fibonacci number."]
            },
            {
              "institutionId": "university-b",
              "key": "fib",
              "label": "Fibonacci B",
              "imageName": "ea-grader-fib-b:v1",
              "manifestPath": "/app/grader/manifest.json",
              "summary": "Institution B grader.",
              "details": ["Return the nth Fibonacci number."]
            }
          ]
        }
        """;

        Files.writeString(configFile, json);

        List<GraderDefinition> graders = createLoader().loadGraders(configFile);

        assertEquals(2, graders.size());
        assertEquals("university-a", graders.get(0).getInstitutionId());
        assertEquals("university-b", graders.get(1).getInstitutionId());
    }

    /**
     * Verifies that CPU requests cannot exceed CPU limits.
     * Expected behavior: invalid resource settings are rejected with a clear validation error.
     */
    @Test
    void loadGraders_requestGreaterThanLimit_throwsException() throws Exception {
        Path configFile = tempDir.resolve("graders.json");

        String json = """
                {
                  "graders": [
                    {
                      "key": "fib",
                      "label": "Fibonacci",
                      "imageName": "ea-grader-fibbonaci:v1",
                      "manifestPath": "/app/grader/manifest.json",
                      "summary": "Dynamic programming warm-up.",
                      "details": ["Return the nth Fibonacci number."],
                      "cpuRequestMilli": 600,
                      "cpuLimitMilli": 500,
                      "memoryRequestMb": 128,
                      "memoryLimitMb": 512
                    }
                  ]
                }
                """;

        Files.writeString(configFile, json);

        GraderConfigLoader loader = createLoader();

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> loader.loadGraders(configFile)
        );

        assertTrue(ex.getMessage().contains("cpuRequestMilli greater than cpuLimitMilli"));
    }

    /**
     * Verifies that each grader must include a frontend summary.
     * Expected behavior: missing summary text causes loader validation to fail.
     */
    @Test
    void loadGraders_missingSummary_throwsException() throws Exception {
        Path configFile = tempDir.resolve("graders.json");

        String json = """
                {
                  "graders": [
                    {
                      "key": "fib",
                      "label": "Fibonacci",
                      "imageName": "ea-grader-fibbonaci:v1",
                      "manifestPath": "/app/grader/manifest.json",
                      "details": ["Return the nth Fibonacci number."]
                    }
                  ]
                }
                """;

        Files.writeString(configFile, json);

        GraderConfigLoader loader = createLoader();

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> loader.loadGraders(configFile)
        );

        assertTrue(ex.getMessage().contains("missing a summary"));
    }

    /**
     * Verifies that grader detail bullets cannot be blank.
     * Expected behavior: the loader rejects invalid details entries instead of exposing empty UI text.
     */
    @Test
    void loadGraders_blankDetailsEntry_throwsException() throws Exception {
        Path configFile = tempDir.resolve("graders.json");

        String json = """
                {
                  "graders": [
                    {
                      "key": "fib",
                      "label": "Fibonacci",
                      "imageName": "ea-grader-fibbonaci:v1",
                      "manifestPath": "/app/grader/manifest.json",
                      "summary": "Dynamic programming warm-up.",
                      "details": ["   "]
                    }
                  ]
                }
                """;

        Files.writeString(configFile, json);

        GraderConfigLoader loader = createLoader();

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> loader.loadGraders(configFile)
        );

        assertTrue(ex.getMessage().contains("invalid details entry"));
    }

    /**
     * Verifies that a config file must define at least one grader.
     * Expected behavior: an empty grader list is rejected as unusable configuration.
     */
    @Test
    void loadGraders_emptyGradersList_throwsException() throws Exception {
        Path configFile = tempDir.resolve("graders.json");

        String json = """
                {
                  "graders": []
                }
                """;

        Files.writeString(configFile, json);

        GraderConfigLoader loader = createLoader();

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> loader.loadGraders(configFile)
        );

        assertTrue(ex.getMessage().contains("At least one grader must be defined"));
    }

    /**
     * Verifies that optional runtime resource fields receive platform defaults.
     * Expected behavior: omitted timeout, CPU, and memory settings are filled with safe default values.
     */
    @Test
    void loadGraders_omittedResourceFields_appliesDefaults() throws Exception {
        Path configFile = tempDir.resolve("graders.json");

        String json = """
                {
                  "graders": [
                    {
                      "key": "fib",
                      "label": "Fibonacci",
                      "imageName": "ea-grader-fibbonaci:v1",
                      "manifestPath": "/app/grader/manifest.json",
                      "summary": "Dynamic programming warm-up.",
                      "details": ["Return the nth Fibonacci number."]
                    }
                  ]
                }
                """;

        Files.writeString(configFile, json);

        List<GraderDefinition> graders = createLoader().loadGraders(configFile);
        GraderDefinition grader = graders.get(0);

        assertEquals(10, grader.getTimeoutSeconds());
        assertEquals(100, grader.getCpuRequestMilli());
        assertEquals(500, grader.getCpuLimitMilli());
        assertEquals(128, grader.getMemoryRequestMb());
        assertEquals(512, grader.getMemoryLimitMb());
    }

    @Test
    void loadGraders_optionalLanguage_trimsAndPreservesValue() throws Exception {
        Path configFile = tempDir.resolve("graders.json");

        String json = """
                {
                  "graders": [
                    {
                      "key": "fib-java",
                      "label": "Fibonacci Java",
                      "imageName": "ea-grader-fib-java:v1",
                      "language": " java ",
                      "manifestPath": "/app/grader/manifest.json",
                      "summary": "Read n from stdin and print Fibonacci.",
                      "details": ["Submit a single Java file."]
                    }
                  ]
                }
                """;

        Files.writeString(configFile, json);

        GraderDefinition grader = createLoader().loadGraders(configFile).get(0);

        assertEquals("java", grader.getLanguage());
    }

    /**
     * Verifies that the Fibonacci performance grader can load with stricter resource settings.
     * Expected behavior: configured timeout, CPU, and memory limits are preserved.
     */
    @Test
    void loadGraders_fibonacciPerformanceConfig_preservesResourceLimits() throws Exception {
        Path configFile = tempDir.resolve("graders.json");

        String json = """
                {
                  "graders": [
                    {
                      "key": "fib-performance",
                      "label": "Fibonacci Performance",
                      "imageName": "ea-grader-fib-performance:v1",
                      "manifestPath": "/app/grader/manifest.json",
                      "summary": "Return Fibonacci numbers efficiently.",
                      "details": ["Use an iterative or dynamic programming approach."],
                      "timeoutSeconds": 6,
                      "cpuRequestMilli": 100,
                      "cpuLimitMilli": 500,
                      "memoryRequestMb": 64,
                      "memoryLimitMb": 128
                    }
                  ]
                }
                """;

        Files.writeString(configFile, json);

        GraderDefinition grader = createLoader().loadGraders(configFile).get(0);

        assertEquals("fib-performance", grader.getKey());
        assertEquals("Fibonacci Performance", grader.getLabel());
        assertEquals("ea-grader-fib-performance:v1", grader.getImageName());
        assertEquals(6, grader.getTimeoutSeconds());
        assertEquals(100, grader.getCpuRequestMilli());
        assertEquals(500, grader.getCpuLimitMilli());
        assertEquals(64, grader.getMemoryRequestMb());
        assertEquals(128, grader.getMemoryLimitMb());
    }

    /**
     * Verifies that the dedicated memory demo grader loads with an intentionally low memory limit.
     * Expected behavior: configured timeout, CPU, and 64Mi memory request/limit are preserved.
     */
    @Test
    void loadGraders_memoryDemoConfig_preservesResourceLimits() throws Exception {
        Path configFile = tempDir.resolve("graders.json");

        String json = """
                {
                  "graders": [
                    {
                      "key": "memory-demo",
                      "label": "Memory Limit Demo",
                      "imageName": "ea-grader-memory-demo:v1",
                      "manifestPath": "/app/grader/manifest.json",
                      "summary": "Demonstrate Kubernetes memory-limit failures.",
                      "details": ["Use this grader to trigger RESOURCE_LIMIT failures."],
                      "timeoutSeconds": 30,
                      "cpuRequestMilli": 100,
                      "cpuLimitMilli": 500,
                      "memoryRequestMb": 64,
                      "memoryLimitMb": 64
                    }
                  ]
                }
                """;

        Files.writeString(configFile, json);

        GraderDefinition grader = createLoader().loadGraders(configFile).get(0);

        assertEquals("memory-demo", grader.getKey());
        assertEquals("Memory Limit Demo", grader.getLabel());
        assertEquals("ea-grader-memory-demo:v1", grader.getImageName());
        assertEquals(30, grader.getTimeoutSeconds());
        assertEquals(100, grader.getCpuRequestMilli());
        assertEquals(500, grader.getCpuLimitMilli());
        assertEquals(64, grader.getMemoryRequestMb());
        assertEquals(64, grader.getMemoryLimitMb());
    }

    /**
     * Verifies that every grader must have a unique non-empty key.
     * Expected behavior: a missing key fails validation because the backend cannot look the grader up.
     */
    @Test
    void loadGraders_missingKey_throwsException() throws Exception {
        assertInvalidSingleGrader(
                """
                "label": "Fibonacci",
                "imageName": "ea-grader-fibbonaci:v1",
                "manifestPath": "/app/grader/manifest.json",
                "summary": "Dynamic programming warm-up.",
                "details": ["Return the nth Fibonacci number."]
                """,
                "non-empty key"
        );
    }

    /**
     * Verifies that every grader must have a human-readable label.
     * Expected behavior: missing label text fails validation before the frontend receives options.
     */
    @Test
    void loadGraders_missingLabel_throwsException() throws Exception {
        assertInvalidSingleGrader(
                """
                "key": "fib",
                "imageName": "ea-grader-fibbonaci:v1",
                "manifestPath": "/app/grader/manifest.json",
                "summary": "Dynamic programming warm-up.",
                "details": ["Return the nth Fibonacci number."]
                """,
                "missing a label"
        );
    }

    /**
     * Verifies that every grader must specify the Docker image used to run it.
     * Expected behavior: missing imageName fails validation because Kubernetes cannot create the job.
     */
    @Test
    void loadGraders_missingImageName_throwsException() throws Exception {
        assertInvalidSingleGrader(
                """
                "key": "fib",
                "label": "Fibonacci",
                "manifestPath": "/app/grader/manifest.json",
                "summary": "Dynamic programming warm-up.",
                "details": ["Return the nth Fibonacci number."]
                """,
                "missing an imageName"
        );
    }

    /**
     * Verifies that every grader must specify the manifest path passed to the runtime.
     * Expected behavior: missing manifestPath fails validation before grading can start.
     */
    @Test
    void loadGraders_missingManifestPath_throwsException() throws Exception {
        assertInvalidSingleGrader(
                """
                "key": "fib",
                "label": "Fibonacci",
                "imageName": "ea-grader-fibbonaci:v1",
                "summary": "Dynamic programming warm-up.",
                "details": ["Return the nth Fibonacci number."]
                """,
                "missing a manifestPath"
        );
    }

    /**
     * Verifies that timeout values must be positive.
     * Expected behavior: zero or negative timeoutSeconds values are rejected.
     */
    @Test
    void loadGraders_invalidTimeout_throwsException() throws Exception {
        assertInvalidSingleGrader(
                """
                "key": "fib",
                "label": "Fibonacci",
                "imageName": "ea-grader-fibbonaci:v1",
                "manifestPath": "/app/grader/manifest.json",
                "summary": "Dynamic programming warm-up.",
                "details": ["Return the nth Fibonacci number."],
                "timeoutSeconds": 0
                """,
                "invalid timeoutSeconds"
        );
    }

    /**
     * Verifies that memory requests cannot exceed memory limits.
     * Expected behavior: contradictory memory settings are rejected with a validation error.
     */
    @Test
    void loadGraders_memoryRequestGreaterThanLimit_throwsException() throws Exception {
        assertInvalidSingleGrader(
                """
                "key": "fib",
                "label": "Fibonacci",
                "imageName": "ea-grader-fibbonaci:v1",
                "manifestPath": "/app/grader/manifest.json",
                "summary": "Dynamic programming warm-up.",
                "details": ["Return the nth Fibonacci number."],
                "memoryRequestMb": 1024,
                "memoryLimitMb": 512
                """,
                "memoryRequestMb greater than memoryLimitMb"
        );
    }

    private void assertInvalidSingleGrader(String graderFields, String expectedMessage) throws Exception {
        Path configFile = tempDir.resolve("graders.json");
        String json = """
                {
                  "graders": [
                    {
                %s
                    }
                  ]
                }
                """.formatted(graderFields.indent(6));

        Files.writeString(configFile, json);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> createLoader().loadGraders(configFile)
        );

        assertTrue(ex.getMessage().contains(expectedMessage));
    }
}
