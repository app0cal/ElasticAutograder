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