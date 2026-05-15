# Getting Started

Use this guide after completing [Installation](installation.md).

## Run Mode Summary

| Mode | Command | Use When |
| --- | --- | --- |
| Infrastructure only | `docker compose up -d` | You are running backend/frontend locally with Gradle and Vite. |
| Local development | `./gradlew bootRun --args='--spring.profiles.active=local'` and `npm run dev` | You are editing project code. |
| Backend containers | `docker compose --profile app up -d --build` | You want API/workers in Docker. |
| Full stack | `docker compose --profile full up -d --build` | You want a release-style local run with backend and frontend containers. |

Do not run the Compose backend and Gradle backend at the same time; both bind the backend API to port 8080.

## Local Development

Terminal 1, from the project root:

```bash
docker compose up -d
```

Terminal 2, backend:

```bash
cd backend
./gradlew bootRun --args='--spring.profiles.active=local'
```

On Windows Command Prompt:

```bat
cd backend
gradlew bootRun --args="--spring.profiles.active=local"
```

Terminal 3, frontend:

```bash
cd frontend
npm install
npm run dev
```

Frontend: http://localhost:5173

Backend API: http://localhost:8080

Check the setup without creating jobs:

```bash
python scripts/doctor.py
```

Verify one complete grading run:

```bash
python scripts/smoke-test.py
```

Verify the project grader after loading it:

```bash
python scripts/setup-graders.py --grader fib-java-project
python scripts/smoke-test.py --scenario project-java-pass
```

## Full Containerized Stack

Build/load grader images first if you have not already:

```bash
python scripts/setup-graders.py
```

Then start the full profile:

```bash
docker compose --profile full up -d --build
```

The frontend container serves the built React app and proxies `/api` to the backend.

If port 5173 is already in use:

```bash
FRONTEND_PORT=5174 docker compose --profile full up -d --build
```

## Backend Containers Only

```bash
docker compose --profile app up -d --build
```

This starts PostgreSQL, Redis, one API container, and one worker container. Use scaling for the distributed worker demo:

```bash
docker compose --profile app up -d --build --scale backend-worker=3
```

Validate the containerized backend path:

```bash
python scripts/doctor.py
python scripts/smoke-test.py
```

## Optional Dev Profile

Use `dev` when you want backend startup to run grader setup automatically:

```bash
cd backend
./gradlew bootRun --args='--spring.profiles.active=dev'
```

The frontend can open while setup is still running, but upload/run requests return a temporary service-unavailable response until setup finishes.

## Shutdown

```bash
# Infrastructure only
docker compose down

# Backend app profile
docker compose --profile app down

# Full stack profile
docker compose --profile full down

# Optional: delete local kind cluster
kind delete cluster --name elastic-autograder
```

Use `docker compose down -v` only when you intentionally want to delete the local Postgres volume.
