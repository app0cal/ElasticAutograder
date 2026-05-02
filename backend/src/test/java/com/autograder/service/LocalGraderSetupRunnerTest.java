package com.autograder.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalGraderSetupRunnerTest {

    @TempDir
    Path tempDir;

    /**
     * Verifies that setup script discovery works when startup begins at the repository root.
     * Expected behavior: the runner resolves scripts/setup-graders.py from the provided directory.
     */
    @Test
    void resolveSetupScript_findsScriptFromRepoRoot() throws Exception {
        Path scriptPath = tempDir.resolve("scripts").resolve("setup-graders.py");
        Files.createDirectories(scriptPath.getParent());
        Files.writeString(scriptPath, "print('hello')");

        LocalGraderSetupRunner runner = new LocalGraderSetupRunner(true, false, "python");

        Path resolved = runner.resolveSetupScript(tempDir);

        assertEquals(scriptPath.toAbsolutePath().normalize(), resolved);
    }

    /**
     * Verifies that setup script discovery works when startup begins inside the backend directory.
     * Expected behavior: the runner searches parent directories and finds the repository-level script.
     */
    @Test
    void resolveSetupScript_findsScriptFromBackendDirectory() throws Exception {
        Path scriptPath = tempDir.resolve("scripts").resolve("setup-graders.py");
        Path backendDir = tempDir.resolve("backend");
        Files.createDirectories(scriptPath.getParent());
        Files.createDirectories(backendDir);
        Files.writeString(scriptPath, "print('hello')");

        LocalGraderSetupRunner runner = new LocalGraderSetupRunner(true, false, "python");

        Path resolved = runner.resolveSetupScript(backendDir);

        assertEquals(scriptPath.toAbsolutePath().normalize(), resolved);
    }

    /**
     * Verifies that missing setup scripts are reported clearly.
     * Expected behavior: resolveSetupScript throws when no scripts/setup-graders.py can be found.
     */
    @Test
    void resolveSetupScript_throwsWhenScriptMissing() {
        LocalGraderSetupRunner runner = new LocalGraderSetupRunner(true, false, "python");

        assertThrows(IllegalStateException.class, () -> runner.resolveSetupScript(tempDir));
    }

    /**
     * Verifies that disabled startup setup is a no-op.
     * Expected behavior: run returns without looking for scripts or failing startup.
     */
    @Test
    void run_setupDisabled_doesNotThrowWhenScriptMissing() {
        LocalGraderSetupRunner runner = new LocalGraderSetupRunner(false, true, "python");

        assertDoesNotThrow(() -> runner.run(null));
    }

    /**
     * Verifies that setup failures can be non-blocking in local development.
     * Expected behavior: run logs/continues when failOnError is false.
     */
    @Test
    void run_setupEnabledAndFailOnErrorFalse_continuesWhenScriptMissing() {
        LocalGraderSetupRunner runner = new LocalGraderSetupRunner(true, false, "python");

        assertDoesNotThrow(() -> runner.run(null));
    }

    /**
     * Verifies that the Python command must be configured.
     * Expected behavior: a blank command is rejected before launching a process.
     */
    @Test
    void runSetupScript_blankPythonCommand_throwsException() {
        LocalGraderSetupRunner runner = new LocalGraderSetupRunner(true, true, "   ");

        assertThrows(
                IllegalStateException.class,
                () -> runner.runSetupScript(tempDir.resolve("scripts").resolve("setup-graders.py"))
        );
    }

    /**
     * Verifies that non-zero setup script exits are treated as failures.
     * Expected behavior: runSetupScript throws an IllegalStateException containing the exit code.
     */
    @Test
    void runSetupScript_nonZeroExit_throwsException() throws Exception {
        Path scriptPath = tempDir.resolve("scripts").resolve("setup-graders.py");
        Files.createDirectories(scriptPath.getParent());
        Files.writeString(scriptPath, "import sys\nsys.exit(7)\n");

        LocalGraderSetupRunner runner = new LocalGraderSetupRunner(true, true, "python");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> runner.runSetupScript(scriptPath)
        );

        assertEquals("Local grader setup script exited with code 7.", exception.getMessage());
    }
}
