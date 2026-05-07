# Backend Developer Guide

This document gives new developers a practical map of the Spring Boot backend. The backend owns job submission records, grader configuration, Kubernetes job orchestration, result persistence, and API responses used by the frontend.

## Main Responsibilities

- Expose REST API endpoints under `/api`.
- Store job metadata and grading results in PostgreSQL.
- Load available graders from the configured `graders.config-path`, which is `../config/graders.json` for local backend development.
- Stage uploaded submissions temporarily under `grading/uploads`.
- Create Kubernetes ConfigMaps and Jobs for grading.
- Parse grader output and save status, score, test counts, and failure details.
- Provide recent jobs, job details, and downloadable result JSON to the frontend.

## Directory Map

`src/main/java/com/autograder/config`

Spring configuration classes. These load grader configuration, configure JSON handling, and provide shared infrastructure such as the Kubernetes client.

`src/main/java/com/autograder/controller`

REST controllers. `JobController` handles uploads, job execution, job history, job details, result download, and upload cleanup.

`src/main/java/com/autograder/dto`

Data transfer objects returned to the frontend. These keep frontend-facing responses separate from internal model details where needed.

`src/main/java/com/autograder/model`

Core domain objects and enums, including `Job`, `JobStatus`, `FailureReason`, and `GraderDefinition`.

`src/main/java/com/autograder/repository`

Database access layer. `JobRepository` extends Spring Data JPA and provides job queries.

`src/main/java/com/autograder/service`

Backend service logic. This includes grader lookup, local grader setup, and Kubernetes orchestration.

`src/main/resources`

Spring application configuration. The `local`, `dev`, and `prod` profiles define different runtime settings.

`src/test/java/com/autograder`

Automated tests. Unit tests cover controller, repository, configuration, and service behavior. The integration/system test exercises the Python grader runtime pipeline.

`grading`

Temporary uploaded submissions used while jobs are running. Public grader manifests and runtime build files live in root `../graders`.

## Request Flow

1. The frontend uploads a submission and selected grader key to `POST /api/jobs/upload`.
2. The backend validates the grader key through `GraderRegistry`.
3. The backend writes the upload under `grading/uploads` and creates a queued `Job` row.
4. The frontend starts grading through `POST /api/jobs/run/{id}`.
5. `Fabric8GradingOrchestrator` creates a Kubernetes ConfigMap from the submission.
6. The orchestrator creates a Kubernetes Job using the selected grader image and manifest.
7. The backend waits for completion, reads grader logs, parses JSON output, and updates the `Job`.
8. The frontend reads job history, details, and downloadable results from the backend API.

## Backend Profiles

Use `local` for normal local development:

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

Use `dev` when you want the backend to rebuild/load grader images during startup. Grader options can load in the frontend while this is running, but submission actions wait behind a temporary service-unavailable response until setup finishes:

```bash
./gradlew bootRun --args='--spring.profiles.active=dev'
```

Use `prod` only when environment variables such as `DB_URL` are configured for a deployed environment.

## Tests And Coverage

Run all backend tests:

```bash
./gradlew test --no-daemon
```

Run tests and generate JaCoCo coverage:

```bash
./gradlew test jacocoTestReport --no-daemon
```

Open the HTML coverage report at:

```text
backend/build/reports/jacoco/test/html/index.html
```

## Adding A New Grader

1. Add a grader entry to `../config/graders.json`.
2. Create a matching manifest folder under `../graders/<grader-key>/`.
3. Add a `manifest.json` using the format described in `../graders/manifestGuide.md`.
4. Build/load grader images with:

```bash
python3 ../scripts/setup-graders.py --grader <grader-key>
```

5. Restart the backend so the new grader configuration is loaded.

## Development Notes

- Keep API changes aligned with the frontend helpers under `../frontend/src/api`.
- Keep database changes aligned with scripts under `../init`.
- Add or update tests when controller behavior, grader configuration, job persistence, or orchestration behavior changes.
- Temporary files under `grading/uploads` are runtime artifacts and should not be committed.
