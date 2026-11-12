ALTER TABLE reviews ADD COLUMN head_sha VARCHAR(64);

CREATE TABLE webhook_deliveries (
    id UUID PRIMARY KEY,
    delivery_id VARCHAR(100) NOT NULL UNIQUE,
    event_type VARCHAR(50) NOT NULL,
    action VARCHAR(50),
    repository_github_id VARCHAR(255),
    pull_request_number INTEGER,
    processing_status VARCHAR(30) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE,
    next_attempt_at TIMESTAMP WITH TIME ZONE,
    error_category VARCHAR(50),
    safe_error_message TEXT
);

CREATE INDEX idx_webhook_deliveries_status
    ON webhook_deliveries(processing_status, next_attempt_at, received_at);

CREATE INDEX idx_reviews_pull_request_head_sha
    ON reviews(pull_request_id, head_sha);
