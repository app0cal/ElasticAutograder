# Elastic Autograder
This project is an open source framework to handle grading jobs concurrently. The framework is designed to be as extendible as possible while building a large majority of the components for you! 

## One-Time Installs (per machine)

Install these first before running anything in this repo.  
Additionally, there is currently a **Windows ONLY setup script** to download/check a majority of these dependencies **except Docker Desktop**.

### Required Dependencies & Download Links

#### Windows only script to download a majority of dependencies + manually download Docker Desktop

This script currently requires **`winget`**.

If `winget` is missing, this setup script will not work until `winget` is available.

If `winget` is not recognized in PowerShell:

1. Install or update **App Installer** from the Microsoft Store.
2. Open a new PowerShell window and run:
   ```powershell
   winget --version
   ```
3. If `winget` is still missing, run:
   ```powershell
   Add-AppxPackage -RegisterByFamilyName -MainPackage Microsoft.DesktopAppInstaller_8wekyb3d8bbwe
   ```
4. Open a new PowerShell window and verify again:
   ```powershell
   winget --version
   ```

Docker Desktop should be downloaded **manually**.  
This is the only manual required download because it deals with machine-specific setup such as system requirements, virtualization, and WSL support.

- [Docker Desktop (official docs)](https://docs.docker.com/desktop/)
- [Install Docker Desktop on Windows (official)](https://docs.docker.com/get-started/get-docker/)

This script downloads/checks a majority of the remaining dependencies automatically:

Must be run in windows powershell terminal from root project directory
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\setup-beta.ps1
```
If the setup script installs a new dependency but the command is still not recognized, close and reopen PowerShell or restart VS Code, then verify the command again. \
\
This script checks/downloads the following:

- Node.js
- npm
- Python
- Java 21
- `kubectl`
- `kind`

It does **not** install Docker Desktop.

---

#### Docker Desktop / Docker
Used to run local containers such as PostgreSQL and Redis.  
Docker is needed for running the local PostgreSQL database.

- [Docker Desktop (official docs)](https://docs.docker.com/desktop/)
- [Install Docker Desktop on Windows (official)](https://docs.docker.com/get-started/get-docker/)

After installation, verify:

```bash
docker --version
```

---

#### Java 21
Required for running the local Spring Boot backend we use.

- [Download from the official site](https://www.oracle.com/java/technologies/javase/jdk21-archive-downloads.html)

After installation, verify:

```bash
java -version
```

---

#### Python 3
Used for backend scripting.

- [Download from the official site](https://www.python.org/downloads/)

After installation, verify:

```bash
python --version
```

---

#### Node.js
Used for running the React + Vite frontend.

Recommended: install a recent LTS version of Node.js. `npm` comes with Node.js.

- [Download from the official site](https://nodejs.org/en/download)

Verify installation with the following in command prompt:

```bash
node -v
npm -v
```

---

#### kind
Used to create and run the local Kubernetes cluster that Elastic Autograder uses during beta testing.

- [kind Quick Start (official docs)](https://kind.sigs.k8s.io/docs/user/quick-start/)

After installation, verify:

```bash
kind --version
```

---

#### kubectl
Used to interact with the local Kubernetes cluster and inspect jobs, pods, and other Kubernetes resources.

- [Official documentation to download](https://kubernetes.io/docs/tasks/tools/)

After installation, verify:

```bash
kubectl version --client
```

---

#### PostgreSQL
Optional. Only needed if you want to connect manually to the database using the `psql` command-line client.  
The local development database itself runs through Docker Compose, so this is **not required** just to run the project.

- [Download from the official site](https://www.postgresql.org/download/)

Note: when installing, avoid setting up a local PostgreSQL server on the same ports used by this project, because it can interfere with the current port setup.

#### Run the following commands to double check everything was installed properly 

```bash
docker --version
node -v
npm -v
java -version
python --version
psql --version
```

### Running the project locally

#### Git clone the main branch repository
```bash
git clone https://github.com/Electrolyte220/ElasticAutograder.git
cd ElasticAutograder
git switch k8s
```

#### Ensure you're inside of the main elastic_autograder directory
Change directories inside of the ElasticAutograder and run the following command
```bash
git switch k8s
```

#### The next steps are easiest with at least two open terminals.

#### Create the kind cluster for the k8s side
Depends on operating system,
(IMPORTANT: This assumes you have no existing cluster or images pre-built, if you do delete them before running scripts)

If on windows, open up a command prompt terminal and run the following
```bash
scripts\setup-k8s.bat
```

If on linux/unix based operating systems run the following
```bash
#!/usr/bin/env bash
set -euo pipefail
python3 scripts/setup_graders.py
```
If you run into any issues refer to the documentation folder/setup-help.md for manually deleting.


#### Run Docker Compose to start the local PostgreSQL database.
```bash
docker compose up -d
docker exec -i ea-postgres psql -U postgres -d elastic_autograder < init/create_job.sql
```

#### Optional: Add mock data to database
```bash
docker exec -i ea-postgres psql -U postgres -d elastic_autograder < init/seed_job.sql
```

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

#### Upload files from mockSubmission folder
Use the sample submissions in `mocksubmission/`.

- Fibonacci fixtures now live in `mocksubmission/fib/`, including `fibpass1.py`, `fibfail1.py`, `brokenfib.py`, and `allfib.zip`
- Count and two-sum fixtures remain in the root `mocksubmission/` folder
