### Local development startup

Start infrastructure from the project root first:

```bash
docker compose up -d
```

By default, Compose starts only Postgres and Redis. The backend commands below start the API on port 8080 for local development.

Do not run the Compose backend and Gradle backend at the same time; both bind the backend API to port 8080.

For a full containerized local stack, build grader images first and then start the `full` profile:

```bash
python scripts/setup-graders.py
docker compose --profile full up -d --build
```

The frontend container proxies `/api` to the backend container. If port 5173 is already in use, set `FRONTEND_PORT=5174` when starting the full profile.

Use this full profile for release-style local runs. Use the terminal split below when actively editing backend or frontend code.

### The next steps require at least two open terminals.

**Terminal 1 — Frontend**
```bash
cd frontend
npm install
npm run dev
```

**Terminal 2 — Backend (Windows Command Prompt / cmd, fast local restarts)**
```bat
cd backend
gradlew bootRun --args="--spring.profiles.active=local"
```

**Terminal 2 — Backend (PowerShell or Linux/macOS shell, fast local restarts)**
```bash
cd backend
./gradlew bootRun --args='--spring.profiles.active=local'
```

**Optional: Backend with automatic grader setup on startup**
Use the `dev` profile when you want the backend to rebuild and load grader images automatically on startup. The frontend can still open while this is running, but job upload/run requests return a temporary `503` until grader setup is ready.

```bash
cd backend
./gradlew bootRun --args='--spring.profiles.active=dev'
```

If you are using the `local` profile and want a one-off rebuild on startup, override the property directly:

```bash
cd backend
./gradlew bootRun --args='--spring.profiles.active=local --graders.setup-on-startup=true'
```

#### Open the local development site

Frontend: http://localhost:5173  
Backend API: http://localhost:8080

If the frontend URL is different, check the Vite terminal output.
