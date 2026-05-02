package com.autograder.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class AutograderSystemTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Verifies that a valid Fibonacci submission passes the full Python grader runtime.
     * Expected behavior: the runtime returns SUCCEEDED with all test cases passing.
     */
    @Test
    void fullAutograderPipeline_fullPass_returnsSucceeded() throws Exception {
        JsonNode output = runGrader("fibpass1.py", "fib");

        assertEquals("SUCCEEDED", output.get("status").asText());
        assertTrue(output.get("tests_total").asInt() > 0);
        assertEquals(output.get("tests_total").asInt(), output.get("tests_passed").asInt());
    }

    /**
     * Verifies that an incorrect Fibonacci submission receives only partial credit.
     * Expected behavior: the runtime returns PARTIAL when some base cases pass but recursive cases fail.
     */
    @Test
    void fullAutograderPipeline_partialCredit_returnsPartial() throws Exception {
        JsonNode output = runGrader("fibfail1.py", "fib");

        assertEquals("PARTIAL", output.get("status").asText());
        assertTrue(output.get("tests_passed").asInt() > 0);
        assertTrue(output.get("tests_total").asInt() > 0);
        assertTrue(output.get("tests_passed").asInt() < output.get("tests_total").asInt());
        assertTrue(output.get("error_message").isNull());
    }

    /**
     * Verifies that an iterative Fibonacci solution passes generated performance cases.
     * Expected behavior: the runtime expands generated cases and returns SUCCEEDED.
     */
    @Test
    void fullAutograderPipeline_fibonacciPerformanceIterative_returnsSucceeded() throws Exception {
        JsonNode output = runGrader("fibpass_iterative.py", "fib-performance");

        assertEquals("SUCCEEDED", output.get("status").asText());
        assertEquals(17, output.get("tests_total").asInt());
        assertEquals(17, output.get("tests_passed").asInt());
    }

    /**
     * Verifies that small hardcoded Fibonacci tables do not pass generated performance cases.
     * Expected behavior: the runtime gives partial credit for small fixed cases and rejects larger generated cases.
     */
    @Test
    void fullAutograderPipeline_fibonacciPerformanceHardcodedSmall_returnsPartial() throws Exception {
        JsonNode output = runGrader("fibhardcoded_small.py", "fib-performance");

        assertEquals("PARTIAL", output.get("status").asText());
        assertEquals(17, output.get("tests_total").asInt());
        assertTrue(output.get("tests_passed").asInt() > 0);
        assertTrue(output.get("tests_passed").asInt() < output.get("tests_total").asInt());
    }

    /**
     * Verifies that the safe memory demo submission passes the direct Python runtime.
     * Expected behavior: the runtime returns SUCCEEDED without exercising the intentional OOM fixture.
     */
    @Test
    void fullAutograderPipeline_memoryDemoPass_returnsSucceeded() throws Exception {
        JsonNode output = runGrader("memorypass.py", "memory-demo");

        assertEquals("SUCCEEDED", output.get("status").asText());
        assertEquals(1, output.get("tests_total").asInt());
        assertEquals(1, output.get("tests_passed").asInt());
    }

    /**
     * Verifies that a mixed-result submission receives partial credit from the runtime.
     * Expected behavior: the runtime returns PARTIAL with some, but not all, tests passing.
     */
    @Test
    void fullAutograderPipeline_mixedScore_returnsPartial() throws Exception {
        Path submission = tempDir.resolve("fibpartial.py");
        Files.writeString(submission, """
                def fib(n):
                    if n == 5:
                        return 5
                    return -1
                """);

        JsonNode output = runGrader(submission.toFile(), "fib");

        assertEquals("PARTIAL", output.get("status").asText());
        assertTrue(output.get("tests_passed").asInt() > 0);
        assertTrue(output.get("tests_passed").asInt() < output.get("tests_total").asInt());
    }

    /**
     * Verifies that submissions missing the required callable fail validation.
     * Expected behavior: the runtime returns FAILED and reports the missing callable function.
     */
    @Test
    void fullAutograderPipeline_missingCallable_returnsFailed() throws Exception {
        Path submission = tempDir.resolve("emptyfile.py");
        Files.writeString(submission, "");
        JsonNode output = runGrader(submission.toFile(), "fib");

        assertEquals("FAILED", output.get("status").asText());
        assertFalse(output.get("validation_passed").asBoolean());
        assertTrue(output.get("error_message").asText().contains("missing callable function"));
    }

    private JsonNode runGrader(String submissionFile, String problem) throws Exception {
        File submission = resolveSubmissionFixture(submissionFile, problem);
        return runGrader(submission, problem);
    }

    private File resolveSubmissionFixture(String submissionFile, String problem) {
        File problemFixture = new File("../mocksubmission/" + problem, submissionFile);
        assertTrue(
                problemFixture.exists(),
                "Submission fixture not found for problem '" + problem + "': " + problemFixture.getPath()
        );

        return problemFixture;
    }

    private JsonNode runGrader(File submission, String problem) throws Exception {
        File runtimeDir = new File("grading/image-build/runtime");
        assertTrue(runtimeDir.exists(), "Runtime directory does not exist: " + runtimeDir.getPath());

        assertTrue(
                submission.exists(),
                "Submission file does not exist for grader '" + problem + "': " + submission.getPath()
        );

        File manifest = new File("grading/image-build/" + problem + "/manifest.json");
        assertTrue(manifest.exists(), "Manifest file does not exist: " + manifest.getPath());

        ProcessBuilder pb = new ProcessBuilder(
                "python",
                "main.py",
                submission.getAbsolutePath(),
                manifest.getAbsolutePath()
        );

        pb.directory(runtimeDir);
        pb.redirectErrorStream(true);

        Process process = pb.start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

        StringBuilder output = new StringBuilder();
        String line;

        while ((line = reader.readLine()) != null) {
            output.append(line);
        }

        int exitCode = process.waitFor();
        assertEquals(0, exitCode);
        assertTrue(output.length() > 0);

        return objectMapper.readTree(output.toString());
    }
}
