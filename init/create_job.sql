DROP TABLE IF EXISTS jobs CASCADE;
DROP TABLE IF EXISTS submission_project_files CASCADE;
DROP TABLE IF EXISTS submission_projects CASCADE;
DROP TABLE IF EXISTS submissions CASCADE;

-- Uploaded submission content shared by backend and worker pods.
CREATE TABLE submissions (
  id BIGSERIAL PRIMARY KEY,
  storage_key UUID NOT NULL UNIQUE,
  original_filename TEXT NOT NULL,
  content TEXT NOT NULL,
  content_type TEXT,
  institution_id TEXT NOT NULL DEFAULT 'local',
  submitted_by TEXT NOT NULL DEFAULT 'anonymous',
  size_bytes BIGINT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Uploaded project archives shared by backend and worker pods.
CREATE TABLE submission_projects (
  id BIGSERIAL PRIMARY KEY,
  storage_key UUID NOT NULL UNIQUE,
  original_filename TEXT NOT NULL,
  institution_id TEXT NOT NULL DEFAULT 'local',
  submitted_by TEXT NOT NULL DEFAULT 'anonymous',
  total_size_bytes BIGINT NOT NULL,
  file_count INT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Files inside a project archive. relative_path preserves the archive layout.
CREATE TABLE submission_project_files (
  id BIGSERIAL PRIMARY KEY,
  project_id BIGINT NOT NULL REFERENCES submission_projects(id) ON DELETE CASCADE,
  relative_path TEXT NOT NULL,
  content TEXT NOT NULL,
  content_type TEXT,
  size_bytes BIGINT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (project_id, relative_path)
);

-- Main jobs table
CREATE TABLE jobs (
  id BIGSERIAL PRIMARY KEY,

  grader_type TEXT NOT NULL,
  original_filename TEXT NOT NULL,
  submission_path TEXT,
  submission_id BIGINT REFERENCES submissions(id),
  submission_project_id BIGINT REFERENCES submission_projects(id),
  submission_kind TEXT NOT NULL DEFAULT 'SINGLE_FILE' CHECK (
    submission_kind IN ('SINGLE_FILE', 'BATCH_FILE', 'PROJECT_ZIP')
  ),
  grader_image TEXT,
  institution_id TEXT NOT NULL DEFAULT 'local',
  submitted_by TEXT NOT NULL DEFAULT 'anonymous',

  status TEXT NOT NULL CHECK (
    status IN ('PENDING', 'QUEUED', 'RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED', 'DEAD_LETTERED', 'CANCELLED')
  ),

  failure_reason TEXT CHECK (
    failure_reason IN (
      'NONE',
      'INVALID_UPLOAD',
      'WRONG_ANSWER',
      'TIMEOUT',
      'RESOURCE_LIMIT',
      'KUBERNETES_ERROR',
      'RESULT_PARSE_ERROR',
      'CONFIG_ERROR',
      'UNKNOWN'
    )
  ),

  failure_message TEXT,

  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  started_at TIMESTAMPTZ,
  finished_at TIMESTAMPTZ,
  queued_at TIMESTAMPTZ,
  attempt_count INT NOT NULL DEFAULT 0,
  max_attempts INT NOT NULL DEFAULT 3,
  last_attempt_at TIMESTAMPTZ,
  queue_message_id TEXT,
  worker_id TEXT,

  score NUMERIC,
  tests_passed INT,
  tests_total INT,

  result_json JSONB,

  k8s_job_name TEXT
);

-- Indexes
CREATE INDEX idx_jobs_status
  ON jobs(status);

CREATE INDEX idx_jobs_failure_reason
  ON jobs(failure_reason);

CREATE INDEX idx_jobs_grader_type
  ON jobs(grader_type);

CREATE INDEX idx_jobs_created_at
  ON jobs(created_at DESC);

CREATE INDEX idx_jobs_queued_at
  ON jobs(queued_at);

CREATE INDEX idx_jobs_worker_id
  ON jobs(worker_id);

CREATE INDEX idx_jobs_submission_id
  ON jobs(submission_id);

CREATE INDEX idx_jobs_submission_project_id
  ON jobs(submission_project_id);

CREATE INDEX idx_jobs_institution_created_at
  ON jobs(institution_id, created_at DESC);

CREATE INDEX idx_jobs_k8s_job_name
  ON jobs(k8s_job_name);

CREATE INDEX idx_submissions_storage_key
  ON submissions(storage_key);

CREATE INDEX idx_submissions_institution
  ON submissions(institution_id);

CREATE INDEX idx_submission_projects_storage_key
  ON submission_projects(storage_key);

CREATE INDEX idx_submission_projects_institution
  ON submission_projects(institution_id);

CREATE INDEX idx_submission_project_files_project_id
  ON submission_project_files(project_id);

-- Helper function to refresh updated_at on row updates
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS trigger AS $$
BEGIN
  NEW.updated_at = NOW();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Recreate trigger safely
DROP TRIGGER IF EXISTS trg_jobs_updated_at ON jobs;

CREATE TRIGGER trg_jobs_updated_at
BEFORE UPDATE ON jobs
FOR EACH ROW
EXECUTE FUNCTION set_updated_at();
