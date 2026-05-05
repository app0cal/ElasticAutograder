-- Adds durable shared submission storage without dropping existing job history.
-- Existing rows keep submission_id NULL; new uploads will write submissions first.

CREATE TABLE IF NOT EXISTS submissions (
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

ALTER TABLE jobs
  ADD COLUMN IF NOT EXISTS submission_id BIGINT;

ALTER TABLE jobs
  DROP CONSTRAINT IF EXISTS jobs_submission_id_fkey;

ALTER TABLE jobs
  ADD CONSTRAINT jobs_submission_id_fkey
  FOREIGN KEY (submission_id)
  REFERENCES submissions(id);

CREATE INDEX IF NOT EXISTS idx_jobs_submission_id
  ON jobs(submission_id);

CREATE INDEX IF NOT EXISTS idx_submissions_storage_key
  ON submissions(storage_key);

CREATE INDEX IF NOT EXISTS idx_submissions_institution
  ON submissions(institution_id);
