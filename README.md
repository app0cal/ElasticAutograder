# Elastic Autograder

[![Java](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-Backend-brightgreen)](https://spring.io/projects/spring-boot)
[![React](https://img.shields.io/badge/React-Frontend-blue)](https://react.dev/)
[![Docker](https://img.shields.io/badge/Docker-Containerized-blue)](https://www.docker.com/)
[![Kubernetes](https://img.shields.io/badge/Kubernetes-Kind%20Ready-326CE5)](https://kind.sigs.k8s.io/)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
![GitHub issues](https://img.shields.io/github/issues/app0cal/ElasticAutograder)

[Getting Started](documentation/start.md) | [Installation](documentation/installation.md) | [Testing](documentation/testing.md) | [Distributed Workers](documentation/distributed-workers.md) | [Burst Testing](documentation/burst-testing.md) | [Future Goals](documentation/futuregoals.md)

Elastic Autograder is an open source grading platform for running many programming submissions through isolated, observable grader jobs.

It combines a Spring Boot API, React frontend, Redis-backed job queue, Postgres job state, and Kubernetes grader execution. The current demo supports Python function graders plus single-file Java and C++ stdin/stdout graders through a shared manifest runtime.

## Highlights

- Multi-language grading for Python, Java, and C++ sample assignments
- Kubernetes Jobs for isolated grader execution and resource limits
- Redis queueing with Postgres-backed atomic job claims
- Distributed worker mode with scalable Docker Compose worker replicas
- React UI for grader selection, uploads, job status, and result details
- Reproducible burst scripts for mixed success, wrong answer, timeout, compile error, runtime error, and memory-limit scenarios

## What can you do with it?

Elastic Autograder is useful for building and demonstrating systems such as coursework autograders, batch evaluation pipelines, sandboxed code runners, and queue-driven worker platforms. It is designed to make job intake, worker concurrency, grader isolation, failure classification, and queue health visible during local experiments.

## Quick Start

Start local infrastructure:

```bash
docker compose up -d
docker exec -i ea-postgres psql -U postgres -d elastic_autograder < init/create_job.sql
python scripts/setup-graders.py
```

Run the backend:

```bash
cd backend
./gradlew bootRun --args='--spring.profiles.active=local'
```

Run the frontend:

```bash
cd frontend
npm install
npm run dev
```

Frontend: http://localhost:5173  
Backend API: http://localhost:8080

## Documentation

- [Dependencies](documentation/dependencies.md)
- [Installation](documentation/installation.md)
- [Getting Started](documentation/start.md)
- [Mock Testing](documentation/testing.md)
- [Distributed Worker Demo](documentation/distributed-workers.md)
- [Burst And Failure Testing](documentation/burst-testing.md)
- [Current Goals](documentation/goals.md)
- [Future Goals](documentation/futuregoals.md)

## Project Status

The project is a local-first autograder demo with a focus on distributed workers, isolated grader execution, and realistic failure handling. Java and C++ support currently targets single-file submissions; project-based submissions are a future goal.

## Contributing

Issues and pull requests are welcome. For planned work and accepted tradeoffs, see [Future Goals](documentation/futuregoals.md).
