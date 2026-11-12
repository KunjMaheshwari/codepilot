ALTER TABLE review_jobs ADD COLUMN processing_heartbeat_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX idx_review_jobs_processing_heartbeat
    ON review_jobs(status, processing_heartbeat_at);
