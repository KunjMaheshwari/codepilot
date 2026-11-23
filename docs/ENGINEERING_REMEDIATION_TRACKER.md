# Engineering Remediation Tracker

Tracks all open GitHub issues (`#4`–`#31`), open contributor PR `#33`, and baseline/CI defects discovered during the engineering audit.

| ID | Category | Description | Priority | Status | Related PR | Tests |
|---|---|---|---|---|---|---|
| `DEF-01` | Frontend E2E | `reviews-settings.spec.ts` `/job` mock returned `COMPLETED` on initial metadata fetch, racing queued-state assertion | P2 | PR_OPEN | #34 | `npx playwright test` (15/15 PASS) |
| `DEF-02` | Security | `RateLimitFilter.clientIp()` trusted spoofable raw `X-Forwarded-For` instead of `request.getRemoteAddr()` | P2 | PR_OPEN | #35 | `RateLimitFilterTest` |
| `DEF-03` | CI / Test Isolation | `SecurityAuthorizationTest` leaked Mockito `SecurityContext` into `SecurityContextHolder`, breaking `AiReviewEngineIntegrationTest` (`usera@example.com`) | P1 | PR_OPEN | #39 | `SecurityAuthorizationTest`, `AiReviewEngineIntegrationTest` |
| `#4` | Backend Tests | Add unit tests for `RepositoryService` | P3 | PR_OPEN | #36 | `RepositoryServiceTest` (3 tests) |
| `#5` | Backend Tests | Add unit tests for `PullRequestService` | P3 | PR_OPEN | #36 | `PullRequestServiceTest` (3 tests) |
| `#6` | Backend Tests | Add unit tests for `ReviewService` | P3 | PR_OPEN | #36 | `ReviewServiceTest` (5 tests) |
| `#7` | Backend Tests | Add unit tests for `GitHubWebhookService` | P3 | PR_OPEN | #36 | `GitHubWebhookServiceTest` (3 tests) |
| `#8` | Backend Tests | Add unit tests for `WebhookDeliveryStateService` | P3 | PR_OPEN | #36 | `WebhookDeliveryStateServiceTest` (4 tests) |
| `#9` | Security Tests | Test `RateLimitFilter` request windows | P3 | PR_OPEN | #35 | `RateLimitFilterTest` (3 tests) |
| `#10` | Security Tests | Add focused `SecurityConfig` tests | P3 | PR_OPEN | #35 | `SecurityConfigTest` (1 test) |
| `#11` | Backend Tests | Test `ReviewJobRepositoryFacade` delegation | P3 | PR_OPEN | #36 | `ReviewJobRepositoryFacadeTest` (1 test) |
| `#12` | AI Tests | Test `DiffParser` CRLF line mapping (and fix context line offset) | P2 | PR_OPEN | #33 | External contributor PR #33 (do not duplicate) |
| `#13` | AI Tests | Test `DiffParser` rename headers | P3 | PR_OPEN | #37 | `DiffParserTest.parsesRenameHeadersAcrossNestedPathsWithEdits` |
| `#14` | AI Tests | Test `DiffParser` deleted-file handling | P3 | PR_OPEN | #37 | `DiffParserTest.deletedFilePreservesOriginalPathAndTracksRemovedLines` |
| `#15` | AI Tests | Test validator handling of empty/blank finding fields | P3 | PR_OPEN | #37 | `ReviewResponseValidatorTest.rejectsWhitespaceOnlyRequiredFindingFields` |
| `#16` | AI Tests | Test validator severity normalization | P3 | PR_OPEN | #37 | `ReviewResponseValidatorTest.normalizesMixedCaseSeverityCategoryAndConfidence` |
| `#17` | Frontend Tests | Add API client error-path tests | P3 | BLOCKED | — | Blocked on Wave 2 `025-frontend-unit-harness.md` |
| `#18` | Frontend Tests | Add tests for dashboard stat cards | P3 | BLOCKED | — | Blocked on Wave 2 `025-frontend-unit-harness.md` |
| `#19` | Frontend Tests | Add tests for `StatusBadge` variants | P3 | BLOCKED | — | Blocked on Wave 2 `025-frontend-unit-harness.md` |
| `#20` | Frontend Tests | Add tests for `EmptyState` accessibility | P3 | BLOCKED | — | Blocked on Wave 2 `025-frontend-unit-harness.md` |
| `#21` | Frontend Tests | Add tests for `ThemeToggle` | P3 | BLOCKED | — | Blocked on Wave 2 `025-frontend-unit-harness.md` |
| `#22` | Frontend Tests | Add tests for the health route | P3 | BLOCKED | — | Blocked on Wave 2 `025-frontend-unit-harness.md` |
| `#23` | Frontend Tests | Test dashboard error recovery UI | P3 | BLOCKED | — | Blocked on Wave 2 `025-frontend-unit-harness.md` |
| `#24` | Docs | Add a CodePilot glossary | P3 | PR_OPEN | #40 | `docs/GLOSSARY.md` |
| `#25` | DevOps | Add dependency update automation | P3 | PR_OPEN | #38 | `.github/dependabot.yml` |
| `#26` | DX | Add optional pre-commit checks | P3 | PR_OPEN | #38 | `.pre-commit-config.yaml` |
| `#27` | DX | Add a portable task runner | P3 | PR_OPEN | #38 | `Makefile` |
| `#28` | Docs | Add a review-pipeline sequence diagram | P3 | PR_OPEN | #40 | `docs/REVIEW_PIPELINE.md` |
| `#29` | Docs | Add authenticated API curl examples | P3 | PR_OPEN | #40 | `docs/API_CURL_EXAMPLES.md` |
| `#30` | Docs | Add a local troubleshooting guide | P3 | PR_OPEN | #40 | `docs/TROUBLESHOOTING.md` |
| `#31` | Docs | Document how to add finding categories | P3 | PR_OPEN | #40 | `docs/FINDING_CATEGORIES.md` |
