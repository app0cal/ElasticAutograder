# Frontend Developer Guide

The frontend is a React/Vite app for selecting graders, uploading submissions, viewing job history, and inspecting results.

## Local Development

```bash
npm install
npm run dev
```

Default URL:

```text
http://localhost:5173
```

The local Vite app calls the backend at:

```text
http://localhost:8080/api
```

## API Base URL

API helpers import `API_BASE` from `src/api/config.js`.

Default:

```text
http://localhost:8080/api
```

Override for a Vite build:

```bash
VITE_API_BASE_URL=/api npm run build
```

The Docker `full` profile builds the frontend with `/api`, and nginx proxies `/api` to the backend.

## Checks

```bash
npm run lint
npm run build
```

The production build may warn about a large JavaScript chunk. That warning is currently informational.

## Docker

The frontend image is built from `frontend/Dockerfile` and served with nginx.

```bash
docker compose --profile full up -d --build
```

If port 5173 is already in use:

```bash
FRONTEND_PORT=5174 docker compose --profile full up -d --build
```
