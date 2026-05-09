# Docker Compose

Docker Compose is used for local infrastructure and optional containerized app runs.

## Profiles

| Command | Starts |
| --- | --- |
| `docker compose up -d` | PostgreSQL and Redis |
| `docker compose --profile app up -d --build` | PostgreSQL, Redis, backend API, backend worker |
| `docker compose --profile full up -d --build` | PostgreSQL, Redis, backend API, backend worker, frontend |

Plain `docker compose up -d` does not create the kind cluster and does not run grader pods. Grader pods run in kind after `scripts/setup-graders.py` has built and loaded grader images.

## Shutdown

Profiles matter when stopping services.

```bash
# Stop default infrastructure services
docker compose down

# Stop backend API/worker profile services too
docker compose --profile app down

# Stop full stack profile services too
docker compose --profile full down
```

If you start the full profile and then run plain `docker compose down`, profiled containers may remain running. Use the matching profile in the `down` command.

Delete database data only when you intentionally want a reset:

```bash
docker compose --profile full down -v
```

## Status And Logs

```bash
docker compose ps
docker compose --profile full ps
docker compose logs -f postgres redis
docker compose --profile app logs -f backend-api backend-worker
docker compose --profile full logs -f frontend
```

## Port Conflicts

Default ports:

- Frontend: `5173`
- Backend API: `8080`
- Postgres: `5432`
- Redis: `6379`

If the Vite dev server is already using port 5173, start the full profile with a different frontend port:

```bash
FRONTEND_PORT=5174 docker compose --profile full up -d --build
```

Do not run the Gradle backend and Compose backend at the same time because both bind port 8080.

## Restart Patterns

Restart infrastructure:

```bash
docker compose restart postgres redis
```

Rebuild backend containers after code changes:

```bash
docker compose --profile app up -d --build
```

Rebuild the full stack:

```bash
docker compose --profile full up -d --build
```

## kind Is Separate

The kind cluster is not owned by Docker Compose. To remove it:

```bash
kind delete cluster --name elastic-autograder
```
