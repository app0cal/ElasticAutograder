-- Adds mock institution/user ownership columns to existing local databases.
-- Existing rows are assigned to the default local anonymous identity.

ALTER TABLE jobs
  ADD COLUMN IF NOT EXISTS institution_id TEXT NOT NULL DEFAULT 'local';

ALTER TABLE jobs
  ADD COLUMN IF NOT EXISTS submitted_by TEXT NOT NULL DEFAULT 'anonymous';

ALTER TABLE submissions
  ADD COLUMN IF NOT EXISTS institution_id TEXT NOT NULL DEFAULT 'local';

ALTER TABLE submissions
  ADD COLUMN IF NOT EXISTS submitted_by TEXT NOT NULL DEFAULT 'anonymous';

CREATE INDEX IF NOT EXISTS idx_jobs_institution_created_at
  ON jobs(institution_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_submissions_institution
  ON submissions(institution_id);
