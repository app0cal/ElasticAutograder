# Dependencies

Install these before running Elastic Autograder locally.

## Required

### Docker Desktop / Docker

Docker runs the local PostgreSQL and Redis containers, builds grader images, and powers the local kind Kubernetes cluster.

- [Docker Desktop](https://docs.docker.com/desktop/)
- [Install Docker](https://docs.docker.com/get-started/get-docker/)

Verify:

```bash
docker --version
docker compose version
```

### Java 21

Java 21 is required for the Spring Boot backend.

- [OpenJDK 21](https://openjdk.org/projects/jdk/21/)

Verify:

```bash
java -version
```

### Python 3

Python runs setup scripts, burst scripts, and the grader runtime tests.

- [Python downloads](https://www.python.org/downloads/)

Verify:

```bash
python --version
```

If your platform uses `python3`, use `python3` in place of `python` in project commands.

### Node.js And npm

Node.js and npm run the React/Vite frontend.

- [Node.js downloads](https://nodejs.org/en/download)

Verify:

```bash
node -v
npm -v
```

### kind

kind runs the local Kubernetes cluster used by grader jobs.

- [kind quick start](https://kind.sigs.k8s.io/docs/user/quick-start/)

Verify:

```bash
kind --version
```

### kubectl

kubectl is used to inspect and manage the local kind cluster.

- [Install kubectl](https://kubernetes.io/docs/tasks/tools/)

Verify:

```bash
kubectl version --client
```

## Optional

### Git

Required if cloning the repository from source. Not required if using a release archive.

```bash
git --version
```

### PostgreSQL Client

The database server runs through Docker Compose. A local `psql` client is optional because the setup commands use `docker exec`.

```bash
psql --version
```

## Windows Helper Script

`scripts/setup-beta.ps1` can check or install several Windows dependencies, but it does not install Docker Desktop and is not the primary supported setup path. Prefer installing the dependencies above directly.
