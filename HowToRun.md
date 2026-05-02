# Testing And Verification Guide

This document explains how to run the project checks used for the 1.0.0 source/test package. The repository currently includes automated backend unit tests, a backend integration/system test, frontend linting, frontend production build verification, and backend coverage reporting through JaCoCo.

## Backend Test Coverage

Backend tests live under:

```text
backend/src/test/java/com/autograder
```

The test suite includes unit tests for individual backend components and an integration/system test for the grading runtime pipeline.

## Unit Test Example

`JobControllerTest` is a representative unit test class.

Location:

```text
backend/src/test/java/com/autograder/controller/JobControllerTest.java
```

It verifies controller behavior for:

- single-file uploads
- duplicate upload rejection
- `.zip` batch upload
- invalid or empty zip uploads
- job execution state changes
- failure handling
- stored result download behavior

Run only this unit test:

```bash
cd backend
./gradlew test --tests com.autograder.controller.JobControllerTest --no-daemon
```

## Integration/System Test Example

`AutograderSystemTest` is the integration/system test class.

Location:

```text
backend/src/test/java/com/autograder/integration/AutograderSystemTest.java
```

It verifies the full Python grading runtime path:

- selects a sample submission fixture
- selects the matching grader manifest
- runs `grading/image-build/runtime/main.py`
- parses JSON grader output
- verifies pass, fail, partial-credit, and invalid-upload behavior

Run only this integration/system test:

```bash
cd backend
./gradlew test --tests com.autograder.integration.AutograderSystemTest --no-daemon
```

## Run All Backend Tests

From the repository root:

```bash
cd backend
./gradlew test --no-daemon
```

JUnit XML results are generated under:

```text
backend/build/test-results/test
```

The human-readable test report is generated at:

```text
backend/build/reports/tests/test/index.html
```

## Generate Backend Coverage

Run backend tests and generate JaCoCo coverage:

```bash
cd backend
./gradlew test jacocoTestReport --no-daemon
```

Coverage reports are generated at:

```text
backend/build/reports/jacoco/test/html/index.html
backend/build/reports/jacoco/test/jacocoTestReport.xml
backend/build/reports/jacoco/test/jacocoTestReport.csv
```

The HTML report is the easiest version to inspect in a browser. The CSV report is useful for copying summary coverage values into release notes.

## Frontend Verification

The frontend currently does not include a dedicated unit test framework. For the 1.0.0 package, frontend verification is handled through linting and a production build.

Run lint:

```bash
cd frontend
npm run lint
```

Run a production build:

```bash
cd frontend
npm run build
```

Build output is generated under:

```text
frontend/dist
```

## Latest Test Results

Latest local verification was run on April 27, 2026.

| Check | Command | Result |
| --- | --- | --- |
| Backend clean build | `cd backend && ./gradlew clean build --no-daemon` | Passed |
| Backend tests and coverage | `cd backend && ./gradlew test jacocoTestReport --no-daemon` | Passed |
| Frontend lint | `cd frontend && npm run lint` | Passed |
| Frontend production build | `cd frontend && npm run build` | Passed |

Coverage summary:

```text
Instruction coverage: 80.4% (2,533 of 3,149 instructions covered)
Branch coverage: 57.3% (213 of 372 branches covered)
Line coverage: 81.5% (651 of 799 lines covered)
```

## Local Backend Profiles

Use `local` for faster day-to-day backend restarts:

```bash
cd backend
./gradlew bootRun --args='--spring.profiles.active=local'
```

Use `dev` when the backend should rebuild and load grader images automatically on startup. The frontend can open while setup is still running, but job upload/run requests are temporarily unavailable until setup finishes:

```bash
cd backend
./gradlew bootRun --args='--spring.profiles.active=dev'
```

Use this one-off override if you want local mode plus grader setup:

```bash
cd backend
./gradlew bootRun --args='--spring.profiles.active=local --graders.setup-on-startup=true'
```
