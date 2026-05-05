Run the following command in the terminal in same directory as project to do the following
1. Create the table
2. Populate the table with mock data 
```bash
docker exec -i ea-postgres psql -U postgres -d elastic_autograder < init\create_job.sql
docker exec -i ea-postgres psql -U postgres -d elastic_autograder < init\seed_job.sql
```

If your database already exists and you only need to update the allowed job statuses
without dropping existing data, run this instead:
```bash
docker exec -i ea-postgres psql -U postgres -d elastic_autograder < init\update_job_status_constraint.sql
```

That update script is the right choice after pulling schema changes for the newer
job states and failure reasons, such as `PARTIAL`, `CONFIG_ERROR`, and `UNKNOWN`.

If your database already exists and you need to add durable submission storage
without dropping existing job history, run:
```bash
docker exec -i ea-postgres psql -U postgres -d elastic_autograder < init\add_submission_storage.sql
```

If your database already exists and you need to add mock institution/user
ownership columns without dropping existing job history, run:
```bash
docker exec -i ea-postgres psql -U postgres -d elastic_autograder < init\add_mock_identity_ownership.sql
```
