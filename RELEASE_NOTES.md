# Elastic Autograder 1.0.0 Release Notes

Release date: April 27, 2026

Elastic Autograder is a web application for submitting Python solutions, running them through configured grading jobs, and reviewing stored grading results. Version 1.0.0 includes the React frontend, Spring Boot backend, PostgreSQL/Redis local infrastructure, Kubernetes-backed grading orchestration, dynamic grader configuration, and automated backend tests.

## Prerequisites

Install these tools before building or running the project:

- Java 21
- Node.js and npm
- Docker or Docker Desktop
- Python 3
- kind
- kubectl
- Git

Verify the major tools with:

```bash
java -version
node -v
npm -v
docker --version
python --version
kind --version
kubectl version --client
```

## Build From Source

Run these commands from the repository root after cloning the project.

Frontend production build:

```bash
cd frontend
npm install
npm run build
```

Backend build and tests:

```bash
cd backend
./gradlew clean build
```

Backend tests with coverage:

```bash
cd backend
./gradlew test jacocoTestReport --no-daemon
```

The backend coverage report is generated at:

```text
backend/build/reports/jacoco/test/html/index.html
```

## Local Run Instructions

Start the local PostgreSQL and Redis services from the repository root:

```bash
docker compose up -d
docker exec -i ea-postgres psql -U postgres -d elastic_autograder < init/create_job.sql
```

Optionally seed sample job data:

```bash
docker exec -i ea-postgres psql -U postgres -d elastic_autograder < init/seed_job.sql
```

Create the local Kubernetes cluster and grader images.

Windows:

```bat
scripts\setup-k8s.bat
```

Linux/macOS:

```bash
bash scripts/setup-k8s.sh
```

If the shell script is unavailable for your environment, run the grader setup directly:

```bash
python3 scripts/setup-graders.py
```

Run the frontend:

```bash
cd frontend
npm install
npm run dev
```

Run the backend with the local profile:

```bash
cd backend
./gradlew bootRun --args='--spring.profiles.active=local'
```

Run the backend with automatic grader setup:

```bash
cd backend
./gradlew bootRun --args='--spring.profiles.active=dev'
```

Open the application at:

```text
http://localhost:5173
```

The backend API runs at:

```text
http://localhost:8080
```

## Working Features

- Upload a single Python submission file.
- Upload a `.zip` batch and create one grading job per submission.
- Select a grader from dynamic grader configuration.
- Run grading jobs through Kubernetes-backed grader containers.
- View recent job history.
- Open a dedicated job details page.
- Display status, score, test counts, failure reason, and failure message.
- Download stored result JSON.
- Clean up staged upload files after grading.
- Run backend unit and integration/system tests.
- Generate backend test coverage with JaCoCo.

## Test Results

Latest local verification was run on April 27, 2026.

| Check | Command | Result |
| --- | --- | --- |
| Backend clean build | `cd backend && ./gradlew clean build --no-daemon` | Passed |
| Backend tests and coverage | `cd backend && ./gradlew test jacocoTestReport --no-daemon` | Passed |
| Frontend lint | `cd frontend && npm run lint` | Passed |
| Frontend production build | `cd frontend && npm run build` | Passed |

Coverage summary from JaCoCo:

```text
Instruction coverage: 80.4% (2,533 of 3,149 instructions covered)
Branch coverage: 57.3% (213 of 372 branches covered)
Line coverage: 81.5% (651 of 799 lines covered)
```

## Known Issues

- No known release-blocking issues at the time of this 1.0.0 source package.
- Frontend `npm run build` may warn that the generated JavaScript chunk is larger than 500 kB. This does not block the build.
- The current automated coverage report covers backend tests only. The frontend has lint/build verification but no dedicated frontend unit test framework in this release.

## Repository And Issue Tracking

Source repository:

```text
https://github.com/Electrolyte220/ElasticAutograder
```

Issue tracking is handled through GitHub Issues in the same repository. The repository includes issue templates for bug reports and TODO/enhancement items under `.github/ISSUE_TEMPLATE/`.
