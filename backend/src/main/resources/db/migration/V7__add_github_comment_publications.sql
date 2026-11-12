CREATE TABLE github_comment_publications (
    id UUID PRIMARY KEY,
    review_id UUID NOT NULL REFERENCES reviews(id) ON DELETE CASCADE,
    pull_request_id UUID NOT NULL REFERENCES pull_requests(id) ON DELETE CASCADE,
    repository_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    finding_id UUID REFERENCES review_findings(id) ON DELETE CASCADE,
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    publication_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    head_sha VARCHAR(64),
    file_path VARCHAR(1024),
    line_number INTEGER,
    github_comment_id BIGINT,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(500),
    published_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_github_comment_pub_review_id ON github_comment_publications(review_id);
CREATE INDEX idx_github_comment_pub_pr_sha ON github_comment_publications(pull_request_id, head_sha);

