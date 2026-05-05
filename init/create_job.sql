DROP TABLE IF EXISTS jobs CASCADE;
DROP TABLE IF EXISTS submissions CASCADE;

-- Uploaded submission content shared by backend and worker pods.
CREATE TABLE submissions (
  id BIGSERIAL PRIMARY KEY,
  storage_key UUID NOT NULL UNIQUE,
  original_filename TEXT NOT NULL,
  content TEXT NOT NULL,
  content_type TEXT,
  size_bytes BIGINT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Main jobs table
CREATE TABLE jobs (
  id BIGSERIAL PRIMARY KEY,

  grader_type TEXT NOT NULL,
  original_filename TEXT NOT NULL,
  submission_path TEXT,
  submission_id BIGINT REFERENCES submissions(id),
  grader_image TEXT,

  status TEXT NOT NULL CHECK (
    status IN ('PENDING', 'QUEUED', 'RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED')
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

CREATE INDEX idx_jobs_submission_id
  ON jobs(submission_id);

CREATE INDEX idx_jobs_k8s_job_name
  ON jobs(k8s_job_name);

CREATE INDEX idx_submissions_storage_key
  ON submissions(storage_key);

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
