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