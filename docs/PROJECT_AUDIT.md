# CodePilot Project Audit

**Audit scope:** repository state at `9e52e55` on branch `chore/repository-audit`
**Mode:** inspection only
**Evidence basis:** source, manifests, migrations, tests, Git metadata, and attempted validation commands. No application source code was modified.

## Executive summary

CodePilot is a partially implemented monorepo with a Spring Boot backend and a Next.js frontend. The backend has a useful domain skeleton (users, GitHub accounts, repositories, pull requests, reviews, findings, and jobs), OAuth client configuration, GitHub diff retrieval, AES-GCM token encryption, diff parsing, and an AI response validator. The frontend is primarily a static dashboard using hard-coded mock data.

The repository is not an MVP-ready working system. The backend test/build command fails during compilation because Lombok-generated logging and accessors are not available to the compiler in the current environment. The frontend lint command cannot start because `node_modules`/ESLint are absent. There is no webhook controller, Redis dependency or configuration, asynchronous worker, Docker/Compose definition, CI workflow, Playwright suite, GitHub repository synchronization endpoint, or frontend API client.

The highest-risk issues are the predictable fallback encryption key, an OAuth principal type mismatch in account disconnect handling, globally scoped dashboard counts, synchronous review execution, missing webhook authenticity handling, and swallowed review failures. These are blockers for secure, multi-user operation and reliable automated review processing.

## Actual technology stack

| Area | Observed implementation | Assessment |
|---|---|---|
| Backend | Java 21, Spring Boot 3.2.3, Spring MVC, Spring Security, OAuth2 client, Spring Data JPA, Bean Validation, Flyway | Declared and partially wired |
| Persistence | PostgreSQL runtime; H2 test dependency; one Flyway migration | Schema foundation exists; test/runtime parity is unverified |
| AI | Custom `RestTemplate` Gemini client and Jackson parsing | Client path exists, but public provider methods are stubs and no live test exists |
| GitHub | OAuth client and `RestTemplate` REST reads | Account linking and read-only repository/PR/diff calls exist; sync, writes, comments, and webhooks do not |
| Frontend | Next.js 16.4.5 App Router, React 19.3.0, TypeScript, Tailwind 4, Recharts, Radix/Base UI components | Presentational shell only; no backend integration |
| Redis/background jobs | No dependency, configuration, queue, listener, or worker found | Not implemented |
| Testing | JUnit/Spring Boot Test/Mockito/Security Test dependencies and backend tests | No frontend tests, Playwright, Testcontainers, or Redis integration tests |
| Operations | No Dockerfile, Compose file, CI workflow, health/metrics/tracing configuration, or deployment manifest | Not implemented |

## Architecture overview

The backend follows a conventional controller → service → repository/entity layout. `SecurityConfig` provisions GitHub OAuth login and a session-based security filter chain. OAuth user data is persisted through `GitHubIntegrationService`, which encrypts the access token. Review creation reaches `CodeReviewOrchestrator`, which creates a `ReviewJob` database row and immediately executes the GitHub diff → parser → Gemini provider → finding persistence path in the request thread.

The frontend has App Router pages under `frontend/src/app`, reusable dashboard/layout/UI components, and static mock data in `frontend/src/lib/mock-data.ts`. There is no fetch/axios client, server action, route handler, authentication guard, or API base URL. Consequently, the two applications are not connected at runtime.

## Component-by-component findings

### Repository structure and hygiene

- The repository has sensible `backend/` and `frontend/` boundaries and conventional Spring package organization.
- `IMPLEMENTATION_PLAN.md` describes a larger target architecture than the implementation. It calls for Redis, webhooks, Docker Compose, CI/CD, Playwright, repository enablement, and review detail pages that are absent.
- Tracked operational/debug artifacts include `backend/mvn_output.log`, `backend/mvn_output2.log`, `clean_workspace.sh`, `find_files.js`, `fix.js`, `generate_missing.sh`, `run_compile_11.js`, `show*.txt`, `test_find.txt`, and Git/debug output files. They increase noise and may expose development history or stale diagnostics.
- A generated `backend/target/` directory exists in the workspace but is ignored and not tracked. It should not be used as evidence of a current successful build.

### Frontend

- Landing page navigation is present, but “Sign in with GitHub” is disabled in `frontend/src/app/page.tsx`; the only active action goes directly to `/dashboard`.
- Dashboard cards, activity chart, and recent reviews consume `mockDashboardStats`, `mockActivityData`, and `mockRecentReviews` from `frontend/src/lib/mock-data.ts`.
- `/dashboard/repositories`, `/dashboard/reviews`, and `/dashboard/settings` are empty-state placeholders with no loading, error, mutation, or API behavior.
- `dashboard/error.tsx` and `dashboard/loading.tsx` provide route-level states, but no page initiates a request that could use them.
- Header avatar, notifications, settings, support, and logout are decorative menu items. The avatar points at an external shadcn image.
- No API integration, typed response models, authentication state, CSRF handling, pagination, review detail view, finding view, repository connection flow, or webhook configuration is implemented.
- `next.config.ts` contains no backend proxy or runtime API configuration. There is no frontend test or Playwright directory.

### Backend API and services

- Controllers expose list/detail reads for repositories, pull requests, reviews, findings, and dashboard stats, plus review creation and GitHub disconnect.
- `RepositoryService`, `PullRequestService`, and `ReviewService` attempt owner-scoped lookups using the authenticated GitHub email.
- There is no endpoint to import/synchronize GitHub repositories or pull requests into the local tables. The exposed repository list only reads rows already in PostgreSQL.
- `DashboardService` uses repository-wide `count()` calls and hard-codes `criticalIssues(0L)`, so dashboard data is not tenant-scoped and is not semantically complete.
- `ReviewService.createReview` executes processing synchronously. Its catch block logs the exception but leaves the review status unchanged, while `finally` always writes `completedAt`, including failed processing.
- `GlobalExceptionHandler` correctly avoids returning exception details for the generic exception path, but the broad catch-all can hide operational distinctions and there is no structured error/correlation logging.
- DTO validation is minimal: `CreateReviewRequest` only checks non-null UUID; pagination limits, sort allowlists, and request size/rate controls are absent.

### AI review pipeline

- `DiffParser` filters selected binary/lock files and caps each parsed diff at approximately 100 KB. It tracks added line numbers for validation.
- `ReviewPromptBuilder` requests a JSON array with severity, category, file, line, evidence, impact, fix, and confidence fields and includes a prompt-injection warning.
- `ReviewResponseValidator` parses fenced/plain JSON, validates required fields, normalizes unknown category/severity values, and rejects findings whose file/line is not in the parsed added lines.
- `GeminiCodeReviewProvider.reviewDiff` reaches a private HTTP implementation with a three-attempt 429 retry loop.
- `GeminiCodeReviewProvider.performReview` returns an empty list and `performRawReview` returns an empty string. These public provider operations are incomplete even though `reviewDiff` uses the private path.
- The Gemini response path returns empty findings for missing candidate content rather than treating an invalid provider response as a failed review. It also sleeps synchronously and has no configured HTTP timeouts, token budget, circuit breaker, request size policy, or structured response schema enforcement at the API boundary.
- `MockAiReviewEngine` is a static placeholder that produces a fabricated `WARNING` finding, a severity not accepted by `ReviewResponseValidator` and not represented in the documented severity set.
- No AI output is posted back to GitHub. Findings are only persisted locally.

### GitHub OAuth and API integration

- `SecurityConfig` configures GitHub OAuth with `user:email,repo` scope and persists users/accounts through a custom OAuth user service.
- `GitHubIntegrationService` encrypts the OAuth access token before persistence and decrypts it only for API calls.
- `GitHubApiClient` implements read calls for `/user/repos`, pull request metadata, and pull request diff media type. It has a basic retry loop but no connect/read timeout configuration, rate-limit header handling, pagination safeguards, or typed GitHub response models.
- No repository import, pull request listing from GitHub, webhook registration, webhook receiver, signature verification, comment/review posting, installation-token model, or permission re-check exists.
- `AuthController.disconnectGitHub` requests `@AuthenticationPrincipal User`, while the configured OAuth principal is `DefaultOAuth2User`; this is an incompatible principal type and is a likely runtime failure for disconnect.

### Database and migrations

- `V1__init_schema.sql` covers the primary entities and foreign keys, including cascading deletes for account/repository/review ownership paths.
- Entity names and columns broadly align with the migration, including `ReviewJob` and `ReviewSettings`.
- Status, severity, category, and confidence are unconstrained strings. Invalid states can be stored, and the mock engine already demonstrates an unsupported severity.
- The migration has single-column indexes for common foreign keys but lacks composite indexes aligned with owner-scoped review queries and lacks uniqueness for some natural integration keys beyond the existing repository/account constraints.
- The access token column stores ciphertext but has no key version, rotation metadata, or explicit encryption scheme metadata.
- Only one migration exists; schema evolution, rollback strategy, seed/reference data, and migration verification are absent.
- Tests use H2 while production uses PostgreSQL. The migration and JPA validation behavior were not successfully exercised because compilation fails.

### Redis, jobs, and webhooks

- `ReviewJob` and `ReviewJobService` are database persistence abstractions, not a queue.
- There is no Redis starter, Redis configuration, queue producer, consumer, retry/dead-letter policy, worker executor, or idempotency key.
- There is no webhook endpoint or HMAC signature validation. Automatic review of opened/synchronized pull requests is therefore not implemented.

### Security and operational controls

- CSRF protection uses a cookie token repository and CORS is restricted to `http://localhost:3000`, which is reasonable for local development but not environment-configurable.
- The fallback encryption key and OAuth client placeholders in `application.yml` make unsafe startup possible if required environment variables are omitted.
- There is no rate limiting, audit trail, webhook replay protection, secret rotation, security headers policy, production origin configuration, health/readiness endpoint, metrics, tracing, or structured log redaction policy.
- GitHub and Gemini calls use bearer/API credentials but do not define client timeouts. A slow upstream can consume servlet threads.
- No Docker, CI/CD, dependency scanning, image scanning, or deployment configuration is present.

## Critical bugs and architectural weaknesses

| Priority | Finding | Evidence | Consequence |
|---|---|---|---|
| P0 | Current backend build is broken | `mvn test -q` failed compilation in `GeminiCodeReviewProvider.java` and `ReviewResponseValidator.java` for Lombok-generated `log` and entity accessors | No verified backend artifact or test execution |
| P0 | Predictable encryption fallback | `backend/src/main/resources/application.yml` supplies a fixed fallback `codepilot.encryption.key` | Tokens are recoverable if deployed without an override |
| P1 | OAuth principal mismatch | `AuthController.disconnectGitHub` expects `User`; `SecurityConfig` returns `DefaultOAuth2User` | Disconnect endpoint can fail before service execution |
| P1 | Review failures are misrepresented | `ReviewService.processReview` catches without setting `FAILED`, then always sets `completedAt` | Failed AI/GitHub work can appear complete or remain `PROCESSING` |
| P1 | Multi-tenant dashboard leakage | `DashboardService` uses global `count()` operations | Users can see aggregate counts across accounts |
| P1 | Target automation architecture absent | No webhook/Redis/worker symbols, dependencies, or endpoints | Automatic PR reviews cannot operate |
| P1 | Frontend is disconnected | No frontend API references; dashboard uses mock data | Users cannot authenticate, connect repositories, or view real reviews |

## Security findings

| Severity | Finding | Evidence and impact |
|---|---|---|
| CRITICAL | Default token-encryption key | `application.yml` contains a fixed fallback key. An omitted environment variable should fail closed, not encrypt production tokens with a public predictable value. |
| HIGH | OAuth disconnect principal type mismatch | The controller asks Spring to inject a domain `User`, but OAuth login creates a `DefaultOAuth2User`. This can break account revocation and leave tokens connected. |
| HIGH | Missing webhook authentication | No webhook endpoint or HMAC verification exists. Once webhook processing is added, accepting unsigned events would permit forged review jobs; this control is currently entirely absent. |
| HIGH | Cross-tenant dashboard aggregates | `DashboardService` counts all reviews, findings, and repositories without authenticated owner filtering. This is an authorization/data-isolation defect. |
| MEDIUM | Unsafe OAuth fallback configuration | `GITHUB_CLIENT_ID` and `GITHUB_CLIENT_SECRET` have placeholder defaults. Startup should fail when credentials are absent in non-test environments. |
| MEDIUM | No rate limiting or abuse controls | Publicly reachable authenticated APIs and future AI/GitHub calls have no per-user limits, quotas, or request-size controls. |
| MEDIUM | No upstream timeouts | GitHub and Gemini `RestTemplate` clients are constructed without connect/read timeouts, enabling resource exhaustion through slow upstream responses. |
| LOW | CORS and redirect URLs are hard-coded | Localhost origins and redirect targets are embedded in Java configuration, complicating safe environment deployment and increasing configuration drift risk. |

## Testing assessment

- Backend unit and Spring tests are present for encryption, GitHub integration, authorization lookup, controller access, validator parsing, and orchestration.
- The backend test suite was **NOT RUN to completion**: `cd backend && mvn test -q` failed during main-source compilation because Lombok-generated `log` variables and accessors were unresolved. Therefore no test result is evidence of passing behavior.
- Frontend lint was **NOT RUN to completion**: `cd frontend && npm run lint` failed with `eslint: command not found`. Dependencies are not installed in the workspace.
- No frontend unit/component tests, Playwright tests, Testcontainers tests, Redis integration tests, webhook signature tests, OAuth callback tests, or end-to-end user-flow tests were found.
- Existing orchestration tests mock the entire GitHub/AI path and do not verify persistence transaction boundaries, async behavior, idempotency, rate limits, or real Gemini response handling.

## Deployment readiness

**Not ready.** There is no container build, local service composition, CI pipeline, environment schema, secret provisioning, migration release process, health check, observability setup, autoscaling/worker deployment, or production OAuth redirect configuration. The backend also has a reproducible compilation failure in the current environment.

## Technical debt

1. Replace string status/severity/category fields with validated enums or database constraints and define state transitions.
2. Remove or quarantine tracked debug logs/scripts and establish artifact hygiene rules.
3. Introduce typed GitHub/Gemini contracts, timeouts, bounded retries with jitter, and error classification.
4. Separate request acceptance from background review processing and add idempotency.
5. Add tenant-aware query methods for every aggregate and detail path.
6. Connect the frontend to typed API clients and implement auth/loading/error/empty states based on real responses.
7. Add migration/entity compatibility checks against PostgreSQL rather than relying only on H2.

## Recommended improvements

### P0

- Make compilation deterministic and restore annotation processing or remove the dependency on generated members; then run the backend suite.
- Remove all production-secret fallbacks. Require a strong, validated encryption key and fail startup when it is absent or invalid.
- Fix authentication principal handling and add an explicit current-user resolver.
- Scope every dashboard and review query by the authenticated user.

### P1

- Implement repository/PR synchronization with pagination and ownership checks.
- Add signed, replay-resistant GitHub webhooks and enqueue idempotent jobs.
- Add Redis-backed worker processing, durable status transitions, retry/dead-letter behavior, and cancellation/timeout policy.
- Complete Gemini provider methods, strict schema validation, bounded payloads, and safe failure semantics.
- Build real frontend API integration for OAuth, repositories, PRs, reviews, findings, and logout.

### P2

- Add PostgreSQL/Testcontainers integration tests, webhook contract tests, API authorization matrix tests, and frontend component tests.
- Add HTTP timeouts, rate-limit/backoff handling, metrics, tracing, correlation IDs, and redacted structured logs.
- Add composite indexes and database constraints based on actual query plans.
- Add Docker/Compose, CI lint/test/build/security gates, and environment-specific configuration.

### P3

- Add GitHub review comments/suggested fixes only after explicit approval workflows, audit logging, and permission review.
- Add review deduplication, diff chunking for very large pull requests, cost controls, and user-configurable review policy.
