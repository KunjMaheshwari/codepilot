# CodePilot Glossary

| Term | Definition |
|---|---|
| **Review** | A code review run for a specific pull request and `headSha` (`QUEUED`, `PROCESSING`, `COMPLETED`, `PARTIAL`, `FAILED`). |
| **ReviewJob** | The asynchronous execution record for a `Review`, tracking `status`, `attemptCount`, `correlationId`, `processingHeartbeatAt`, and `lastErrorCategory`. |
| **ReviewFinding** | A validated AI-generated issue tied to a specific `filePath`, added `lineNumber`, `category`, `severity`, `confidence`, `evidence`, `impact`, and `suggestedFix`. |
| **ParsedFile** | Output of `DiffParser` for a single file in a unified git diff, including `addedLines`, `removedLines`, `changedLineRanges`, and `reviewable` flag. |
| **Dead-Letter Queue (DLQ)** | Redis list (`codepilot:review-jobs:dead-letter`) storing malformed payloads or jobs that exhausted `maxAttempts`. |
| **WebhookDelivery** | Persisted GitHub webhook event keyed by `X-GitHub-Delivery` for idempotency and retry tracking. |
| **Correlation ID** | UUID generated per `ReviewJob` and propagated through worker and orchestrator logs for end-to-end tracing. |
| **Finding Category** | Classification enum validated by `ReviewResponseValidator`: `BUG`, `SECURITY`, `PERFORMANCE`, `RELIABILITY`, `MAINTAINABILITY`, `CODE_QUALITY`. |
| **Severity** | Finding impact level: `CRITICAL`, `HIGH`, `MEDIUM`, `LOW`, `INFO`. |
