# Elastic Autograder

[![Java](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-Backend-brightgreen)](https://spring.io/projects/spring-boot)
[![React](https://img.shields.io/badge/React-Frontend-blue)](https://react.dev/)
[![Docker](https://img.shields.io/badge/Docker-Containerized-blue)](https://www.docker.com/)
[![Kubernetes](https://img.shields.io/badge/Kubernetes-Kind%20Ready-326CE5)](https://kind.sigs.k8s.io/)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
![GitHub issues](https://img.shields.io/github/issues/app0cal/ElasticAutograder)

[Getting Started](documentation/start.md) | [Installation](documentation/installation.md) | [Testing](documentation/testing.md) | [Architecture](documentation/architecture.md) | [Distributed Workers](documentation/distributed-workers.md) | [Burst Testing](documentation/burst-testing.md) | [Future Goals](documentation/futuregoals.md)

Elastic Autograder is an open source grading platform for running programming submissions through isolated, observable grader jobs.

It combines a Spring Boot API, React frontend, Redis-backed job queue, Postgres job state, and Kubernetes grader execution. The current demo supports Python function graders plus single-file Java and C++ stdin/stdout graders through a shared manifest runtime.

## Highlights

- Multi-language grading for Python, Java, and C++ sample assignments
- Kubernetes Jobs for isolated grader execution and resource limits
- Redis queueing with Postgres-backed atomic job claims
- Distributed worker mode with scalable Docker Compose worker replicas
- React UI for grader selection, uploads, job status, and result details
- Reproducible burst scripts for mixed success, wrong answer, timeout, compile error, runtime error, and memory-limit scenarios

## Quick Start

Install dependencies first, then run the one-time setup:

```bash
docker compose up -d
docker exec -i ea-postgres psql -U postgres -d elastic_autograder < init/create_job.sql
python scripts/setup-graders.py
```

Choose one run mode:

| Mode | Command | Use When |
| --- | --- | --- |
| Infrastructure only | `docker compose up -d` | You want Postgres and Redis for Gradle/Vite development. |
| Local development | `cd backend && ./gradlew bootRun --args='--spring.profiles.active=local'` plus `cd frontend && npm run dev` | You are editing backend or frontend code. |
| Backend containers | `docker compose --profile app up -d --build` | You want API/workers in Docker without the frontend container. |
| Full stack | `docker compose --profile full up -d --build` | You want the release-style local app in one Compose profile. |

Frontend: http://localhost:5173

Backend API: http://localhost:8080

Run the following docker compose command to fully build the backend frontend and every part:

```bash
docker compose --profile full up -d --build
```

Do not run the Compose backend and Gradle backend at the same time; both bind the backend API to port 8080.

## Shutdown

```bash
# Infrastructure only
docker compose down

# Backend container profile
docker compose --profile app down

# Full stack profile
docker compose --profile full down

# Optional: remove the local kind cluster too
kind delete cluster --name elastic-autograder
```

Avoid `docker compose down -v` unless you intentionally want to delete the local Postgres data volume.

## Documentation

- [Dependencies](documentation/dependencies.md)
- [Installation](documentation/installation.md)
- [Getting Started](documentation/start.md)
- [Grader Workspace](graders/README.md)
- [Manifest Guide](graders/manifestGuide.md)
- [Testing](documentation/testing.md)
- [Architecture](documentation/architecture.md)
    - [Distributed Workers](documentation/distributed-workers.md)
- [Burst And Failure Testing](documentation/burst-testing.md)
- [Docker Compose](documentation/docker.md)

Additional:
- [Setup Help](documentation/setup-help.md)
- [Release Guide](documentation/release.md)
- [Goals](documentation/futuregoals.md)

## Project Status

The project is a local-first autograder demo with a focus on distributed workers, isolated grader execution, and realistic failure handling. Java and C++ support currently targets single-file submissions; project-based submissions are a future goal.

## Contributing

Issues and pull requests are welcome. For planned work and accepted tradeoffs, see [Future Goals](documentation/futuregoals.md).
