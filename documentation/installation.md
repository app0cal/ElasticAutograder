# Installation

This guide covers first-time setup. For daily startup commands, use [Getting Started](start.md).

## 1. Get The Project

Clone from source:

```bash
git clone https://github.com/app0cal/ElasticAutograder.git
cd ElasticAutograder
```

Or download and unpack a release archive from GitHub when one is available.

## 2. Install Dependencies

Install the required tools listed in [Dependencies](dependencies.md):

- Docker / Docker Compose
- Java 21
- Python 3
- Node.js and npm
- kind
- kubectl

## 3. Start Local Infrastructure

From the project root:

```bash
docker compose up -d
```

Plain `docker compose up -d` starts only PostgreSQL and Redis. It does not start the backend, frontend, or Kubernetes grader pods.

## 4. Initialize The Database

For a fresh local database:

```bash
docker exec -i ea-postgres psql -U postgres -d elastic_autograder < init/create_job.sql
```

Optional sample rows:

```bash
docker exec -i ea-postgres psql -U postgres -d elastic_autograder < init/seed_job.sql
```

Avoid `docker compose down -v` unless you intentionally want to delete the local database volume.

## 5. Create kind And Build Grader Images

The setup script creates the kind cluster if needed, builds grader images, and loads those images into kind:

```bash
python scripts/setup-graders.py
```

On platforms where Python is named `python3`:

```bash
python3 scripts/setup-graders.py
```

Useful options:

```bash
# Build/load only one grader
python scripts/setup-graders.py --grader fib-java
python scripts/setup-graders.py --grader fib-java-project

# Clean Docker rebuild
python scripts/setup-graders.py --no-cache

# Opt into parallel grader image builds
python scripts/setup-graders.py --parallel --build-workers 2
```

The script uses Docker layer cache by default and builds reusable Python, Java, and C++ runtime base images as needed.

## 6. Check The Setup

Run the read-only diagnostic script:

```bash
python scripts/doctor.py
```

It checks local tools, Docker services, kind, Kubernetes access, grader images, and backend health when the API is running.

If you are setting up project submissions, load the project grader and verify it with the project smoke scenario:

```bash
python scripts/setup-graders.py --grader fib-java-project
python scripts/smoke-test.py --scenario project-java-pass
```

## 7. Choose A Run Mode

After installation, use [Getting Started](start.md) to choose one of:

- Gradle backend + Vite frontend for local development
- Compose `app` profile for backend API/workers in containers
- Compose `full` profile for the full local app stack

Do not run multiple backend modes at the same time because they bind port 8080.
