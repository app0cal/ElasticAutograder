## Docker Compose Local Setup (PostgreSQL + Redis)

We will use `docker-compose.yml` to create the containers for our local workflow.

Reminder(this part is more for the readme after when we're done and for future users):
- **PostgreSQL** (database for jobs/results metadata)
- **Redis** (queue for job messages)

> **Important:** `docker-compose.yml` is only for local infra services right now.  
> It does **NOT** create/manage the Kubernetes cluster (`kind`) and it does **not** run grading pods at all.

---

### What `docker-compose.yml` does for us
Rather than everyone manually typing commands to create containers the docker-compose file lets us have a shared setup w the settings below: 

- container names
- ports (important for connecting database and redis to backend)
- environment variables
- volumes (persistent database storage, (this is how postgreSQL keeps the db even after we turn it off temporarily))

This keeps everyone’s local environment consistent.

A lot of the following might seem redundant below but I highly encourage you guys to read over it because in some scenarios stopping with the wrong command can delete database volume which basically wipes out the db from your local machine.

## Common use cases (read this after reading the tutorial below please)
A lot of the commands are the exact same as the ones below but please at least understand why to use each one before you copy paste bc some commands wipe out db which I cant stress enough.
### Start Services again
```bash
docker compose up -d
```

### Check status
```bash
docker compose ps
```

### Stop temporarily (keeps all containers)
```bash
docker compose stop
```

### Restart stopped containers
```bash
docker compose start
```

### Recreate containers (keeps named volumes)
```bash
docker compose down
docker compose up -d
```

### Full reset (deletes all)
```bash
docker compose down -v
docker compose up -d
```

## 1) Start local services (create if needed)
Run this from the project root (where `docker-compose.yml` is located):

```bash
docker compose up -d
```

## 2) Check it's running 
```bash
docker compose ps
```

## 3) Check logs
```bash
docker compose ps
```

## 4) Stop temporarily (keeps containers)
```bash
docker compose stop
```

## 5) Start after stopping
```bash
docker compose start
```

## 6) Recreate Containers (this keeps the DB data)
```bash
docker compose down
docker compose up -d
```

## 7) Resets everything (including Postgres/volume data) 
Be very careful when you use this, save this as a last resort.
```bash
docker compose down -v
docker compose up -d
```
the -v removes volume specifically

## 8) Bugs you might run into (we can index personal bugs we find here)

1. container name already in use, this usually happens if you manually created a container already with the same name so just delete it with docker desktop OR use command line interface. 

2. ports already in use, remove the conflicting container OR change port mapping. HOWEVER, if you do this make sure you note it down or remember bc this can be an easy bug to overlook