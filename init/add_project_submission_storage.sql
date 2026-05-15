-- Adds durable project-zip submission storage without dropping existing job history.
-- Project archives create one job and keep one row per file with its normalized relative path.

CREATE TABLE IF NOT EXISTS submission_projects (
  id BIGSERIAL PRIMARY KEY,
  storage_key UUID NOT NULL UNIQUE,
  original_filename TEXT NOT NULL,
  institution_id TEXT NOT NULL DEFAULT 'local',
  submitted_by TEXT NOT NULL DEFAULT 'anonymous',
  total_size_bytes BIGINT NOT NULL,
  file_count INT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS submission_project_files (
  id BIGSERIAL PRIMARY KEY,
  project_id BIGINT NOT NULL REFERENCES submission_projects(id) ON DELETE CASCADE,
  relative_path TEXT NOT NULL,
  content TEXT NOT NULL,
  content_type TEXT,
  size_bytes BIGINT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (project_id, relative_path)
);

ALTER TABLE jobs
  ADD COLUMN IF NOT EXISTS submission_project_id BIGINT;

ALTER TABLE jobs
  ADD COLUMN IF NOT EXISTS submission_kind TEXT NOT NULL DEFAULT 'SINGLE_FILE';

ALTER TABLE jobs
  DROP CONSTRAINT IF EXISTS jobs_submission_kind_check;

ALTER TABLE jobs
  ADD CONSTRAINT jobs_submission_kind_check
  CHECK (submission_kind IN ('SINGLE_FILE', 'BATCH_FILE', 'PROJECT_ZIP'));

ALTER TABLE jobs
  DROP CONSTRAINT IF EXISTS jobs_submission_project_id_fkey;

ALTER TABLE jobs
  ADD CONSTRAINT jobs_submission_project_id_fkey
  FOREIGN KEY (submission_project_id)
  REFERENCES submission_projects(id);

CREATE INDEX IF NOT EXISTS idx_jobs_submission_project_id
  ON jobs(submission_project_id);

CREATE INDEX IF NOT EXISTS idx_submission_projects_storage_key
  ON submission_projects(storage_key);

CREATE INDEX IF NOT EXISTS idx_submission_projects_institution
  ON submission_projects(institution_id);

CREATE INDEX IF NOT EXISTS idx_submission_project_files_project_id
  ON submission_project_files(project_id);
