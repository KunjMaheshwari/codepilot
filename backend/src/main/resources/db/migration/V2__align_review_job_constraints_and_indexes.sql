CREATE UNIQUE INDEX uq_review_jobs_review_id ON review_jobs(review_id);

CREATE INDEX idx_pull_requests_repository_created_at
    ON pull_requests(repository_id, created_at DESC);

CREATE INDEX idx_reviews_owner_created_at
    ON reviews(pull_request_id, created_at DESC);

CREATE INDEX idx_review_findings_review_severity
    ON review_findings(review_id, severity);

