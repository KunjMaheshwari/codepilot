ALTER TABLE repositories ADD COLUMN description TEXT;
ALTER TABLE repositories ADD COLUMN is_private BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE repositories ADD COLUMN default_branch VARCHAR(255);
ALTER TABLE repositories ADD COLUMN html_url VARCHAR(1024);
ALTER TABLE repositories ADD COLUMN last_synced_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE pull_requests ADD COLUMN base_branch VARCHAR(255);
ALTER TABLE pull_requests ADD COLUMN head_branch VARCHAR(255);
ALTER TABLE pull_requests ADD COLUMN base_sha VARCHAR(64);
ALTER TABLE pull_requests ADD COLUMN head_sha VARCHAR(64);
ALTER TABLE pull_requests ADD COLUMN additions INTEGER;
ALTER TABLE pull_requests ADD COLUMN deletions INTEGER;
ALTER TABLE pull_requests ADD COLUMN changed_files INTEGER;
