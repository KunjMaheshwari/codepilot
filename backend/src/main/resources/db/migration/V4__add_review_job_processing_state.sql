ALTER TABLE review_jobs ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE review_jobs ADD COLUMN started_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE review_jobs ADD COLUMN completed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE review_jobs ADD COLUMN last_error_category VARCHAR(50);
ALTER TABLE review_jobs ADD COLUMN correlation_id VARCHAR(100);

CREATE INDEX idx_review_jobs_status_created_at ON review_jobs(status, created_at);
