package com.autograder.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Local-only startup hook that rebuilds and reloads grader images before the
 * backend is considered ready.
 *
 * This is intentionally restricted to the local profile because it depends on
 * developer tools such as Python, Docker, kind, and kubectl being installed.
 */
@Component
@Profile({"local", "dev"})
public class LocalGraderSetupRunner implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(LocalGraderSetupRunner.class);
    private static final String SETUP_SCRIPT = "scripts/setup-graders.py";
    private static final int MAX_PARENT_LOOKUPS = 4;

    private final boolean setupOnStartup;
    private final boolean failOnError;
    private final String pythonCommand;
    private final LocalGraderSetupStatus setupStatus;

    public LocalGraderSetupRunner(
            @Value("${graders.setup-on-startup:false}") boolean setupOnStartup,
            @Value("${graders.setup-fail-on-error:false}") boolean failOnError,
            @Value("${graders.setup-python-command:python}") String pythonCommand) {
        this(setupOnStartup, failOnError, pythonCommand, new LocalGraderSetupStatus());
    }

    @Autowired
    public LocalGraderSetupRunner(
            @Value("${graders.setup-on-startup:false}") boolean setupOnStartup,
            @Value("${graders.setup-fail-on-error:false}") boolean failOnError,
            @Value("${graders.setup-python-command:python}") String pythonCommand,
            LocalGraderSetupStatus setupStatus) {
        this.setupOnStartup = setupOnStartup;
        this.failOnError = failOnError;
        this.pythonCommand = pythonCommand == null ? "python" : pythonCommand.trim();
        this.setupStatus = setupStatus;

        if (setupOnStartup) {
            this.setupStatus.markSetupRequired();
        }
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!setupOnStartup) {
            setupStatus.markReady();
            logger.info("Local grader setup on startup is disabled.");
            return;
        }

        try {
            Path workingDirectory = Path.of("").toAbsolutePath().normalize();
            Path scriptPath = resolveSetupScript(workingDirectory);

            logger.info("Running local grader setup script: {}", scriptPath);
            runSetupScript(scriptPath);
            setupStatus.markReady();
            logger.info("Local grader setup completed successfully.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handleFailure("Local grader setup was interrupted.", e);
        } catch (Exception e) {
            handleFailure("Local grader setup failed.", e);
        }
    }

    Path resolveSetupScript(Path startingDirectory) {
        Path current = startingDirectory.toAbsolutePath().normalize();

        for (int i = 0; i < MAX_PARENT_LOOKUPS && current != null; i++) {
            Path candidate = current.resolve(SETUP_SCRIPT);
            if (Files.isRegularFile(candidate)) {
                return candidate.normalize();
            }
            current = current.getParent();
        }

        throw new IllegalStateException(
                "Could not find " + SETUP_SCRIPT + " from startup directory " + startingDirectory.toAbsolutePath().normalize());
    }

    void runSetupScript(Path scriptPath) throws IOException, InterruptedException {
        if (pythonCommand.isBlank()) {
            throw new IllegalStateException("graders.setup-python-command must not be blank.");
        }

        ProcessBuilder processBuilder = new ProcessBuilder(pythonCommand, scriptPath.toString());
        processBuilder.directory(scriptPath.getParent().getParent().toFile());
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logger.info("[grader-setup] {}", line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("Local grader setup script exited with code " + exitCode + ".");
        }
    }

    private void handleFailure(String message, Exception cause) {
        setupStatus.markFailed(message);

        if (failOnError) {
            throw new IllegalStateException(message, cause);
        }

        logger.warn("{} Continuing startup because graders.setup-fail-on-error=false", message, cause);
    }
}
