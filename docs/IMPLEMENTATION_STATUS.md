# CodePilot Implementation Status

**Status rule:** each major feature has exactly one status based on inspected code and validation evidence. “Implemented code” is not treated as “working functionality.”

## Feature status matrix

| Feature | Status | Evidence |
|---|---|---|
| Spring Boot backend foundation | BROKEN | `backend/pom.xml` declares Spring Boot 3.2.3/Java 21 and `CodePilotApplication` exists, but `mvn test -q` fails main compilation due to unresolved Lombok-generated members in `GeminiCodeReviewProvider` and `ReviewResponseValidator`. |
| Next.js App Router frontend foundation | PARTIALLY IMPLEMENTED | `frontend/src/app/layout.tsx`, `frontend/src/app/page.tsx`, and dashboard routes/components exist; no successful lint/build was available because `eslint` is not installed. |
| GitHub OAuth login | PARTIALLY IMPLEMENTED | `SecurityConfig` configures GitHub OAuth and persists users/accounts through `oauth2UserService`; the landing page disables sign-in and no end-to-end OAuth test exists. |
| User/session authorization | BROKEN | `RepositoryService.getAuthenticatedUser` resolves the OAuth email and owner-scoped repository queries exist, but `AuthController.disconnectGitHub` expects a domain `User` principal while security creates `DefaultOAuth2User`. |
| GitHub account token encryption | PARTIALLY IMPLEMENTED | `EncryptionService` uses AES-GCM with random IVs and `GitHubIntegrationService.linkAccount` encrypts tokens; `application.yml` has a predictable fallback key and no rotation/versioning. |
| Connect/import GitHub repositories | PARTIALLY IMPLEMENTED | `GitHubApiClient.getUserRepositories` and `GitHubIntegrationService.getRepositoriesForUser` can read GitHub repositories, but no controller imports or persists them and the frontend has no integration. |
| Browse pull requests | PARTIALLY IMPLEMENTED | `PullRequestController` and `PullRequestService` read local `PullRequest` rows; there is no GitHub PR synchronization or frontend API/view. |
| Fetch PR metadata and diffs | PARTIALLY IMPLEMENTED | `GitHubApiClient.getPullRequest` and `getPullRequestDiff` are wired into `GitHubIntegrationService`; no successful live integration evidence, timeout policy, or persisted sync path exists. |
| Diff parsing and changed-line validation | PARTIALLY IMPLEMENTED | `DiffParser.parseDiff` tracks added lines and filters some file types; parsing is heuristic and has no broad edge-case test suite for renames, binary diffs, malformed hunks, or large multi-file PRs. |
| Gemini AI integration | PARTIALLY IMPLEMENTED | `GeminiCodeReviewProvider.reviewDiff` builds an HTTP request to Gemini and parses candidates; `performReview` and `performRawReview` are stubs, configuration lacks a visible key property in the default application file, and the backend currently does not compile. |
| Prompt construction and prompt-injection resistance | PARTIALLY IMPLEMENTED | `ReviewPromptBuilder` requests a structured array and explicitly treats repository content as untrusted; no token budgeting, repository context policy, or prompt regression tests exist. |
| AI response validation | PARTIALLY IMPLEMENTED | `ReviewResponseValidator` parses JSON/fenced JSON and verifies file/added-line membership; it normalizes invalid values instead of rejecting them and has no schema-level bounds for strings, line values, or confidence. |
| Structured findings persistence | PARTIALLY IMPLEMENTED | `ReviewFinding`, `ReviewFindingDto`, `review_findings` migration, and `ReviewFindingRepository` exist; DTO omits evidence, impact, suggested fix, category, and confidence from the client response. |
| Review orchestration | BROKEN | `CodeReviewOrchestrator` wires job creation, GitHub diff, parsing, provider, and persistence, but executes synchronously and depends on a backend that fails compilation; `ReviewService` swallows processing errors and always sets completion time. |
| Review history/detail API | PARTIALLY IMPLEMENTED | `ReviewController` exposes review and finding reads with owner filtering through `ReviewService`; no frontend consumes these endpoints and no status transition/transaction integration test proves behavior. |
| Dashboard statistics | BROKEN | `DashboardService` returns global repository/review/finding counts and hard-codes critical issues to zero; frontend displays unrelated values from `mock-data.ts`. |
| GitHub review comments/suggested fixes | NOT IMPLEMENTED | No GitHub write method, review-comment DTO, approval endpoint, suggested patch model, or UI action exists. |
| GitHub webhooks | NOT IMPLEMENTED | No webhook controller, payload model, HMAC signature validation, event filtering, replay protection, or webhook registration code exists. |
| Redis queue/background worker | NOT IMPLEMENTED | `ReviewJob` is a JPA row and `ReviewJobService` only saves status; no Redis dependency, queue, consumer, worker, or dead-letter mechanism exists. |
| PostgreSQL persistence | PARTIALLY IMPLEMENTED | `V1__init_schema.sql` and JPA entities cover core tables/relationships and indexes; only one migration exists, constraints are weak, and the actual application/test context was not successfully built. |
| Flyway migration consistency | UNVERIFIED | Flyway is configured and V1 is present, but `mvn test` stops at compilation before migration validation and no PostgreSQL integration execution was available. |
| Frontend dashboard | PARTIALLY IMPLEMENTED | Dashboard route, layout, loading/error components, cards, chart, and navigation exist; all displayed data is mock data and no API/auth state is connected. |
| Frontend repositories/reviews/settings | NOT IMPLEMENTED | Routes exist as static empty-state placeholders without controls, API calls, pagination, detail links, or mutations. |
| Frontend authentication/logout | NOT IMPLEMENTED | Sign-in is disabled in `page.tsx`; header logout is a non-functional menu item and no auth context/client exists. |
| Loading/error/empty UX | PARTIALLY IMPLEMENTED | Route-level `loading.tsx`/`error.tsx` and static empty states exist; real request lifecycle states are absent because no requests are made. |
| Backend exception handling | PARTIALLY IMPLEMENTED | `GlobalExceptionHandler` maps not-found and argument errors and hides generic details; validation errors, auth failures, upstream errors, correlation IDs, and structured logging are not specifically handled. |
| Unit/integration testing | PARTIALLY IMPLEMENTED | JUnit/Mockito/Spring tests cover selected services/controllers/validator/encryption; the suite cannot compile, uses mocks for external paths, and has no Testcontainers/Redis/webhook integration. |
| Frontend/E2E testing | NOT IMPLEMENTED | No Jest/RTL/Playwright dependencies or test files are tracked. |
| Docker/deployment | NOT IMPLEMENTED | No Dockerfile, docker-compose, Kubernetes/ACA manifest, or deployment script exists. |
| CI/CD | NOT IMPLEMENTED | No `.github/workflows` CI workflow, build/test gate, dependency scan, or image scan exists. |
| Observability/operations | NOT IMPLEMENTED | No actuator/health, metrics, tracing, alerting, readiness, or deployment logging configuration exists. |
| Git hygiene | PARTIALLY IMPLEMENTED | `.gitignore` covers common build/env files and the worktree was clean, but multiple logs, debug scripts, and generated-analysis artifacts are tracked. No secret literal was found in the scan beyond test placeholder/token-like values and schema field names. |

## Missing dependencies and blockers

### Immediate blockers

1. Backend compilation fails because Lombok-generated `log` fields and entity accessors are not resolved in the current Maven invocation. This prevents tests and runtime verification.
2. Frontend dependencies are not installed; `npm run lint` exits with `eslint: command not found`. No frontend validation result can be claimed.
3. The frontend and backend have no API contract/client connection.
4. No repository/PR synchronization means local browse endpoints have no production data source.
5. No webhook, Redis, worker, or idempotency implementation means automatic review cannot exist.
6. The default encryption key makes a deployment misconfiguration a token-confidentiality failure.

### Missing dependencies/configuration

- Redis client/starter and queue/worker configuration.
- GitHub webhook payload/signature support and write API support.
- Gemini API key property in the default application configuration, with environment-specific secret validation.
- Docker/Compose services for PostgreSQL, Redis, backend, and frontend.
- CI dependencies/commands for Java, Node, lint, tests, security scanning, and artifact publication.
- Playwright and frontend unit-test tooling.
- Production OAuth redirect/origin configuration and secret management.

## Prioritized implementation roadmap

### P0 — restore a secure, verifiable baseline

1. Resolve the reproducible Maven compilation failure and make annotation processing/build output deterministic.
2. Remove unsafe secret fallbacks; require encryption, OAuth, and AI secrets at startup in non-test profiles.
3. Correct principal resolution and centralize authenticated-user lookup.
4. Scope all aggregate and detail queries by user and add authorization matrix tests.
5. Establish PostgreSQL-backed migration verification and a clean test baseline.

### P1 — functional MVP

1. Add typed GitHub repository/PR synchronization with pagination, ownership, and persistence.
2. Add real frontend API client, OAuth entry point, session-aware route behavior, repository browsing, PR selection, review history, and finding detail.
3. Complete Gemini provider methods with bounded requests, strict schema validation, timeouts, and explicit failure statuses.
4. Replace synchronous processing with Redis-backed jobs, worker execution, retry/dead-letter handling, and idempotency.
5. Add signed GitHub webhook intake for opened/synchronized events and safe job enqueueing.
6. Add the minimum integration tests for OAuth, repository/PR sync, webhook signatures, queue processing, and end-to-end review creation.

### P2 — reliability and production quality

1. Add timeout/rate-limit/circuit-breaker behavior for GitHub and Gemini.
2. Add database constraints, composite indexes, status transition validation, and migration checks.
3. Add structured logging, redaction, health/readiness, metrics, traces, and correlation IDs.
4. Add frontend component tests, Playwright flows, accessibility checks, and API contract tests.
5. Add Docker/Compose and CI gates for build, lint, tests, dependency vulnerabilities, and container scanning.

### P3 — future product capabilities

1. Add explicit approval and audit workflows for GitHub comments and suggested fixes.
2. Add AI AutoFix patch generation, review, permission checks, rollback, and audit history.
3. Add cost controls, large-diff chunking, repository policy settings, notifications, and analytics.

## Recommended implementation order

1. Build/toolchain correctness and secret fail-closed behavior.
2. Authentication principal resolution and tenant-safe authorization.
3. PostgreSQL migration/entity validation and repository/PR synchronization.
4. Typed backend API contract and connected frontend data flows.
5. Gemini provider completion and deterministic review state machine.
6. Redis worker, retries, idempotency, and webhook signature validation.
7. Integration/E2E test baseline.
8. Docker, CI/CD, observability, and deployment hardening.
9. Approved GitHub comments and AutoFix as a separately gated feature.

## Validation record

- `cd backend && mvn test -q`: **FAILED before tests** during compilation; unresolved Lombok-generated `log` and accessor members were reported.
- `cd frontend && npm run lint`: **FAILED to start** because `eslint` was not installed.
- Playwright/E2E: **NOT RUN**; no suite exists.
- No source, migration, configuration, or application file was modified during this audit; only this status document and `docs/PROJECT_AUDIT.md` were created.
