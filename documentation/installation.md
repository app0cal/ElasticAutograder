### Running the project locally

#### Git clone the main branch repository
```bash
git clone https://github.com/app0cal/ElasticAutograder.git
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
python3 scripts/setup-graders.py
```
If you run into any issues refer to the documentation folder/setup-help.md for manually deleting.


#### Run Docker Compose to start local infrastructure.

By default, Compose starts only PostgreSQL and Redis. Start the backend separately with Gradle for local development, use the Compose `app` profile for containerized API/workers, or use the `full` profile for the full browser app stack. Do not run multiple backend modes at the same time because they bind port 8080.

If the Vite dev server is already using port 5173, run the full profile with `FRONTEND_PORT=5174`.

```bash
docker compose up -d
docker exec -i ea-postgres psql -U postgres -d elastic_autograder < init/create_job.sql
```

#### Optional: Add mock data to database
```bash
docker exec -i ea-postgres psql -U postgres -d elastic_autograder < init/seed_job.sql
```
