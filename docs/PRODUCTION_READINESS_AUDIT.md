# CodePilot Production-Readiness Audit

**Audit Date:** 2026-11-12  
**Scope:** Full-stack audit of `/Users/kunjmaheshwari/Desktop/codepilot` (`backend/`, `frontend/`, database migrations, queue/worker infrastructure, AI/GitHub integrations, security, tests, and deployment readiness).  
**Status:** Read-only audit (no source or configuration files modified).

---

## 1. Executive Summary

CodePilot is **not ready for production** and **cannot reliably complete core end-to-end user flows in local development** without code and configuration fixes.

While the repository has a clean architectural skeleton (Spring Boot 3.2.3 API + Redis-backed async job queue + PostgreSQL/Flyway + Gemini REST integration + Next.js 16 dashboard), multiple **P0 blockers** prevent a clean build, break authenticated frontend mutations, crash webhook processing on arrival, and break core user journeys:

1. **Backend clean compilation fails** (`backend/pom.xml` specifies `<java.version>25</java.version>` on Java 23.0.2 and Spring Boot 3.2.3).
2. **All mutating frontend API calls fail with `403 Forbidden`** because `SecurityConfig` requires CSRF (`XSRF-TOKEN` / `X-XSRF-TOKEN`) while `frontend/src/lib/api.ts` never sends `X-XSRF-TOKEN`, and `SecurityConfig` does not wire `.cors()`.
3. **Hardcoded `http://localhost:3000`** in `SecurityConfig`, `WebConfig`, and `AuthController` breaks OAuth redirects and CORS in any non-local environment.
4. **Webhook processing throws `TransactionRequiredException`** on the first webhook because `WebhookDeliveryRepository.claim(...)` is `@Modifying` without an active `@Transactional` boundary in `GitHubWebhookService.processPendingEvents()`.
5. **Core user flows are incomplete:**
   - No controller or UI triggers `GitHubIntegrationService.synchronizePullRequests(...)`, so newly synced repositories have **0 local pull requests** and users cannot trigger manual reviews from the UI.
   - `ReviewsPage` never loads findings for already-completed reviews.
   - `CodeReviewOrchestrator` saves findings only to PostgreSQL and **never posts review comments back to GitHub**.
6. **Zero deployment infrastructure:** No `Dockerfile`, `docker-compose.yml`, `.env.example` (backend), or CI/CD workflows exist, and the root directory contains ~15 stray debug/scratch scripts and log files.

---

## 2. Architecture Overview

```
┌──────────────────────┐        HTTP + Cookies         ┌──────────────────────────────────┐
│  Next.js 16 Frontend │ ────────────────────────────► │  Spring Boot 3.2.3 Backend       │
│  (App Router, React  │   (Missing X-XSRF-TOKEN &     │  - Spring Security OAuth2        │
│   19, Tailwind v4)   │    SecurityConfig .cors())    │  - REST Controllers (/api/v1/*)  │
└──────────────────────┘                               │  - Webhook Receiver (/webhooks)  │
                                                       └──────┬───────────────┬───────────┘
                                                              │               │
                 ┌────────────────────────────────────────────┘               │
                 ▼                                                            ▼
┌─────────────────────────────────┐                          ┌────────────────────────────┐
│  PostgreSQL + Flyway (V1..V6)   │                          │  Redis (Lists:             │
│  - users, connected_github_acct │                          │   review-jobs, processing, │
│  - repositories, pull_requests  │                          │   dead-letter)             │
│  - reviews, review_findings     │                          └────────────┬───────────────┘
│  - review_jobs, webhook_deliv.  │                                       │
└─────────────────────────────────┘                                       ▼
                 ▲                                           ┌────────────────────────────┐
                 │                                           │  @Scheduled Workers        │
                 └───────────────────────────────────────────│  - ReviewJobWorker         │
                                                             │  - ReviewJobProducer       │
                                                             │  - GitHubWebhookService    │
                                                             └──────┬──────────────┬──────┘
                                                                    │              │
                                                                    ▼              ▼
                                                           ┌──────────────┐ ┌──────────────┐
                                                           │  GitHub REST │ │ Google Gemini│
                                                           │  API v3      │ │ v1beta API   │
                                                           └──────────────┘ └──────────────┘
```

---

## 3. Critical Issues (P0)

### P0-1: Backend Clean Compilation Fails Due to Invalid `java.version` — **FIXED (Phase 2)**
* **File/module affected:** `backend/pom.xml` (line 19)
* **Exact problem:** `<java.version>25</java.version>` was configured while the environment runs Java 23.0.2 and Spring Boot `3.2.3`. Running `mvn clean test-compile -B` failed immediately with `Fatal error compiling: error: release version 25 not supported`.
* **Fix applied:** Updated `<java.version>25</java.version>` to `<java.version>21</java.version>` (Java 21 LTS, supported by Spring Boot `3.2.3`, Lombok `1.18.36`, ByteBuddy `1.15.11`, and Mockito `5.14.2`).
* **Verification:** `cd backend && mvn clean test -B`, `mvn verify -B`, and `mvn package -B` all succeeded without `-Djava.version` overrides (`javac [debug release 21]`, 55/55 tests passing).

### P0-2: Frontend Mutations Blocked by Missing CSRF Token Header & Unwired Security CORS — **FIXED (Phase 5)**
* **File/module affected:** `backend/src/main/java/com/codepilot/config/SecurityConfig.java`, `backend/src/main/java/com/codepilot/controller/AuthController.java`, `frontend/src/lib/api.ts`
* **Fix applied:** Wired `.cors(Customizer.withDefaults())` with explicit `CorsConfigurationSource` (rejecting wildcards and untrusted origins), configured `CookieCsrfTokenRepository` with `CsrfTokenRequestAttributeHandler` (`csrfRequestAttributeName=null` so `XSRF-TOKEN` is emitted), added `GET /api/v1/auth/csrf`, and updated `frontend/src/lib/api.ts` to fetch/read `XSRF-TOKEN` and send `X-XSRF-TOKEN` on all `POST`/`PUT`/`PATCH`/`DELETE` requests.
* **Verification:** `SecurityHardeningIntegrationTest` verified `XSRF-TOKEN` issuance, `403` on missing/invalid CSRF tokens, `204` on valid CSRF token, and `OPTIONS` CORS allow/reject behavior.

### P0-3: Hardcoded `http://localhost:3000` in Production Backend Security & Controllers — **FIXED (Phase 5)**
* **File/module affected:** `backend/src/main/java/com/codepilot/config/SecurityConfig.java`, `backend/src/main/java/com/codepilot/config/WebConfig.java`, `backend/src/main/java/com/codepilot/controller/AuthController.java`, `backend/src/main/resources/application.yml`
* **Fix applied:** Externalized `codepilot.frontend.url` (`${FRONTEND_URL:http://localhost:3000}`) and `codepilot.cors.allowed-origins` (`${CORS_ALLOWED_ORIGINS:http://localhost:3000}`), added session fixation protection (`migrateSession()`), `HttpOnly`/`Secure`/`SameSite` cookie config, security headers (`X-Frame-Options: DENY`, `nosniff`, `Referrer-Policy`), `POST /api/v1/auth/logout`, `GET /api/v1/auth/me`, session invalidation on `DELETE /api/v1/auth/github`, `RateLimitFilter`, and restricted Swagger UI (`codepilot.security.swagger-public=false` default).
* **Verification:** `SecurityHardeningIntegrationTest` verified logout session/cookie invalidation, `/api/v1/auth/me` token-free output, cross-tenant `404` isolation, and `/v3/api-docs` protection.

### P0-4: Webhook Worker Crashes with `TransactionRequiredException` on First Delivery — **FIXED (Phase 3)**
* **File/module affected:** `backend/src/main/java/com/codepilot/repository/WebhookDeliveryRepository.java` (line 25), `backend/src/main/java/com/codepilot/service/GitHubWebhookService.java` (line 82)
* **Exact problem:** `WebhookDeliveryRepository.claim(...)` was a `@Modifying` native `UPDATE` query without `@Transactional`, throwing `TransactionRequiredException` when invoked from `GitHubWebhookService.processPendingEvents()`. Additionally, `processPendingEvents()` passed the stale pre-claim `candidate` entity (with `attemptCount` still 0) to `process(...)`.
* **Fix applied:** Added `@Transactional` to `WebhookDeliveryRepository.claim(...)` and reloaded `WebhookDelivery` via `findById(candidate.getId())` after a successful claim so `attemptCount` reflects the incremented database value.
* **Verification:** `PostgresRedisInfrastructureIntegrationTest.webhookDeliveryClaimAndProcessExecutesInTransaction` verified against `postgres:17-alpine`.

### P0-5: Manual Pull Request Review Flow Is Broken End-to-End
* **File/module affected:** `backend/src/main/java/com/codepilot/controller/RepositoryController.java`, `backend/src/main/java/com/codepilot/service/GitHubIntegrationService.java` (line 92), `frontend/src/app/dashboard/repositories/page.tsx` (lines 32–38, 82–113)
* **Exact problem:**
  1. `RepositoriesPage` calls `api.getPullRequests(repository.id)` (`GET /api/v1/repositories/{id}/pull-requests`), which queries only the local `pull_requests` table.
  2. `GitHubIntegrationService.synchronizePullRequests(...)` exists in the backend, **but no controller endpoint invokes it**. `GET /api/v1/repositories/{id}/github-pull-requests` fetches from GitHub without persisting to PostgreSQL.
  3. `POST /api/v1/repositories/{id}/reviews` requires a local `PullRequest.id` UUID.
* **User/business impact:** After signing in and syncing repositories, a user sees `"No synchronized pull requests found."` and **cannot trigger a code review from the UI** unless a webhook has already arrived.
* **Recommended fix:** Add `POST /api/v1/repositories/{id}/pull-requests/sync` calling `gitHubIntegrationService.synchronizePullRequests(...)` (or sync on demand when opening a repo / creating a review) and wire a "Sync Pull Requests" action in `RepositoriesPage`.
* **Verification method:** End-to-end test: sync repos -> sync PRs -> trigger review -> verify `Review` and `ReviewJob` are created.

---

## 4. High-Priority Issues (P1)

### P1-1: Pending Job Recovery Fails with `LazyInitializationException` — **FIXED (Phase 3)**
* **File/module affected:** `backend/src/main/java/com/codepilot/repository/ReviewJobRepository.java` (line 17)
* **Exact problem:** `findTop50ByStatusOrderByCreatedAtAsc("PENDING")` did not eager-fetch `review`, `review.pullRequest`, or `review.pullRequest.repository`, causing `LazyInitializationException` in `ReviewJobProducer.recoverPendingJobs()`.
* **Fix applied:** Added `@EntityGraph(attributePaths = {"review", "review.pullRequest", "review.pullRequest.repository"})` to `findTop50ByStatusOrderByCreatedAtAsc`.
* **Verification:** `PostgresRedisInfrastructureIntegrationTest.pendingJobRecoveryAndRedisQueueRoundTrip` verified on PostgreSQL 17 + Redis 7.

### P1-2: Global Heartbeat Prevents Stale Job Recovery Across Workers — **FIXED (Phase 3)**
* **File/module affected:** `backend/src/main/java/com/codepilot/service/ReviewJobWorker.java`, `backend/src/main/java/com/codepilot/service/ReviewJobService.java`, `backend/src/main/java/com/codepilot/repository/ReviewJobRepository.java`
* **Exact problem:** `touchProcessingJobs()` updated `processing_heartbeat_at` for all `PROCESSING` rows globally, preventing stale-job recovery when another worker crashed.
* **Fix applied:** Tracked `activeJobIds` (`ConcurrentHashMap.newKeySet()`) in `ReviewJobWorker` around `orchestrateReview(job)` and scoped `touchProcessingJobs(Collection<UUID> ids)` to `WHERE id IN (:ids) AND status = 'PROCESSING'`.
* **Verification:** `PostgresRedisInfrastructureIntegrationTest.scopedHeartbeatOnlyTouchesSpecifiedProcessingJobs` verified only the specified job's heartbeat is updated.

### P1-3: Single-Threaded `@Scheduled` Starvation & Blocking `Thread.sleep` in Worker — **FIXED (Phase 4)**
* **File/module affected:** `backend/src/main/java/com/codepilot/config/AsyncReviewConfig.java`, `backend/src/main/java/com/codepilot/service/ReviewJobWorker.java`, `backend/src/main/resources/application.yml`
* **Fix applied:** Configured a `ThreadPoolTaskScheduler` bean (`poolSize=4`, `waitForTasksToCompleteOnShutdown=true`) in `AsyncReviewConfig`, removed `Thread.sleep(...)` from `ReviewJobWorker.poll()`, implemented non-blocking delayed retries via Redis ZSET (`codepilot:review-jobs:retry`) with bounded exponential backoff + jitter and `@Scheduled promoteDueRetries()`, and added `processedCount`/`retryScheduledCount`/`deadLetterCount` observability counters.
* **Verification:** `ReviewJobWorkerTest` and `AsyncReliabilityIntegrationTest` verified non-blocking retry scheduling, ZSET promotion, and DLQ handling with zero `Thread.sleep` in worker paths.

### P1-4: Stale `attemptCount` and Self-Invoked `@Transactional` in `GitHubWebhookService` — **FIXED (Phase 4)**
* **File/module affected:** `backend/src/main/java/com/codepilot/service/WebhookDeliveryStateService.java`, `backend/src/main/java/com/codepilot/service/GitHubWebhookService.java`
* **Fix applied:** Extracted `WebhookDeliveryStateService` with `@Transactional` boundaries for `findPendingDeliveries`, `claim`, `markProcessed`, `markIgnored`, and `markFailed`, eliminating self-invocation proxy bypass and preserving accurate `attemptCount` and `nextAttemptAt`.
* **Verification:** `PostgresRedisInfrastructureIntegrationTest` and `AsyncReliabilityIntegrationTest` verified duplicate delivery idempotency, failed delivery retry scheduling, and transactional state transitions on PostgreSQL 17.

### P1-5: AI Findings Are Never Posted Back to GitHub Pull Requests
* **File/module affected:** `backend/src/main/java/com/codepilot/service/CodeReviewOrchestrator.java` (lines 58–70), `backend/src/main/java/com/codepilot/service/GitHubApiClient.java`, `backend/src/main/java/com/codepilot/service/GitHubIntegrationService.java`
* **Exact problem:** `CodeReviewOrchestrator` persists `ReviewFinding` rows to PostgreSQL, but neither `GitHubApiClient` nor `GitHubIntegrationService` has any method to post PR reviews or comments to GitHub (`POST /repos/{owner}/{repo}/pulls/{pull_number}/reviews`).
* **User/business impact:** The core SaaS value proposition ("automated AI code reviews on GitHub PRs") does not deliver feedback to GitHub; developers must open the CodePilot web UI to see anything.
* **Recommended fix:** Implement `createPullRequestReview(...)` in `GitHubApiClient` / `GitHubIntegrationService` and post a summary + inline comments (with graceful fallback to issue comment if line mapping fails) after `findingRepository.saveAll(findings)`.
* **Verification method:** WireMock/integration test verifying `POST /repos/{owner}/{repo}/pulls/{number}/reviews` is called with validated findings.

### P1-6: `ReviewsPage` Never Loads Findings for Completed Reviews
* **File/module affected:** `frontend/src/app/dashboard/reviews/page.tsx` (lines 21–34, 55–73)
* **Exact problem:** `api.getFindings(review.id)` is only called inside the polling `useEffect` when an active review transitions out of `activeStatuses`. When a user navigates to `/dashboard/reviews` after a review is already `COMPLETED` or `PARTIAL`, `findings[review.id]` is `undefined` and there is no button or effect to load findings.
* **User/business impact:** Users cannot view AI review findings for any completed review in the UI.
* **Recommended fix:** Fetch `api.getFindings(review.id)` on expand/click (or eagerly for visible completed/partial reviews) and add a loading/error state per review card.
* **Verification method:** Playwright/RTL test rendering `ReviewsPage` with a `COMPLETED` review and verifying findings are fetched and displayed.

### P1-7: `test` Profile With Hardcoded Encryption Key Ships Inside Production Artifact — **FIXED (Phase 2)**
* **File/module affected:** `backend/src/main/resources/application-test.yml` -> moved to `backend/src/test/resources/application-test.yml`
* **Exact problem:** `application-test.yml` was located in `src/main/resources/` instead of `src/test/resources/`, causing H2 test config and the hardcoded test encryption key to be packaged into the production JAR.
* **Fix applied:** Moved `application-test.yml` from `backend/src/main/resources/` to `backend/src/test/resources/`.
* **Verification:** Inspected `backend/target/backend-0.0.1-SNAPSHOT.jar` via `jar tf`; only `BOOT-INF/classes/application.yml` is packaged (`application-test.yml` is excluded), and all 55 tests continue to pass under the `test` profile.

### P1-8: `ReviewResponseValidator` Aborts Entire Review on Single Line-Number Off-by-One or Long Title
* **File/module affected:** `backend/src/main/java/com/codepilot/ai/ReviewResponseValidator.java` (lines 67, 112–121)
* **Exact problem:**
  1. `isValidFinding` throws `IllegalArgumentException("Finding references a non-added line")` if *any* single finding from Gemini has a line number not in `file.getAddedLines()` (a very common LLM off-by-one error), failing the entire review.
  2. `title` is not truncated to `255` chars (the DB `VARCHAR(255)` limit in `V1__init_schema.sql`), risking `DataIntegrityViolationException` on `saveAll`.
* **User/business impact:** Valid findings are discarded and entire reviews fail intermittently due to minor LLM formatting/line-offset quirks.
* **Recommended fix:** Filter out/log invalid findings instead of failing the whole batch (or snap to nearest diff line if within +/-2 lines), and enforce/truncate `title` (<= 255) and `filePath` (<= 1024) before persistence.
* **Verification method:** Unit test with mixed valid/invalid findings and >255-char title verifying valid findings are preserved and saved.

---

## 5. Medium-Priority Issues (P2)

### P2-1: `SecurityConfig` Identifies Users by Email Instead of Immutable `githubUserId` — **FIXED (Phase 5)**
* **File/module affected:** `backend/src/main/java/com/codepilot/config/SecurityConfig.java`, `backend/src/main/java/com/codepilot/service/RepositoryService.java`, `backend/src/main/java/com/codepilot/repository/ConnectedGitHubAccountRepository.java`
* **Fix applied:** `oauth2UserService` and `RepositoryService.getAuthenticatedUser()` now resolve `User` via immutable `githubUserId` (`ConnectedGitHubAccountRepository.findByGithubUserId` with `@EntityGraph(attributePaths = "user")`), falling back to email only when `id` is absent or on initial registration.
* **Verification:** `SecurityHardeningIntegrationTest` verified `/api/v1/auth/me` and cross-tenant isolation using `githubUserId`-linked OAuth2 login.

### P2-2: Unused `ReviewSettings` Entity & Missing Settings Enforcement
* **File/module affected:** `backend/src/main/java/com/codepilot/model/ReviewSettings.java`, `backend/src/main/java/com/codepilot/repository/ReviewSettingsRepository.java`, `backend/src/main/java/com/codepilot/service/CodeReviewOrchestrator.java`, `frontend/src/app/dashboard/settings/page.tsx`
* **Exact problem:** `review_settings` table (`auto_review_enabled`, `strict_mode`) exists in Flyway `V1` and JPA, but no controller, service, webhook check, prompt builder, or frontend UI reads or updates it.
* **User/business impact:** Users cannot disable auto-reviews per repository or toggle strict mode.
* **Recommended fix:** Add `GET/PUT /api/v1/repositories/{id}/settings`, check `autoReviewEnabled` in `GitHubWebhookService`, pass `strictMode` to `ReviewPromptBuilder`, and expose controls on `SettingsPage`.
* **Verification method:** Integration test toggling `autoReviewEnabled=false` and verifying webhook skips review creation.

### P2-3: `GlobalExceptionHandler` Swallows 500 Exceptions Without Logging
* **File/module affected:** `backend/src/main/java/com/codepilot/exception/GlobalExceptionHandler.java` (lines 65–75)
* **Exact problem:** `handleGlobalException(Exception ex, WebRequest request)` returns `500 Internal Server Error` without logging `ex`.
* **User/business impact:** Production 500 errors leave zero stack traces or diagnostics in server logs.
* **Recommended fix:** Add `@Slf4j` and `log.error("Unhandled exception for request {}", path, ex);`.
* **Verification method:** Trigger an unhandled runtime exception in a `@WebMvcTest` and verify stack trace is logged.

### P2-4: `CodeReviewOrchestrator` Does Not Set `Review.startedAt` / `completedAt` in Async Mode
* **File/module affected:** `backend/src/main/java/com/codepilot/service/CodeReviewOrchestrator.java` (lines 35–82)
* **Exact problem:** `ReviewService` only sets `startedAt`/`completedAt` in synchronous mode (`asyncEnabled=false`). In async mode, `orchestrateReview` updates `review.status` (`COMPLETED`/`PARTIAL`/`FAILED`) without setting `review.startedAt` or `review.completedAt`.
* **User/business impact:** Review duration and completion timestamps are always `null` (`"—"`) in the UI and API responses.
* **Recommended fix:** Set `review.setStartedAt(ZonedDateTime.now())` when transitioning to `PROCESSING` and `review.setCompletedAt(ZonedDateTime.now())` on completion/failure, and save `review` when transitioning to `PROCESSING`.
* **Verification method:** Run `CodeReviewOrchestratorTest` and assert `startedAt` and `completedAt` are populated.

### P2-5: Repository Sync Only Fetches Page 1 (Max 100) and Never Deactivates Removed Repos
* **File/module affected:** `backend/src/main/java/com/codepilot/controller/RepositoryController.java` (lines 52–58), `backend/src/main/java/com/codepilot/service/GitHubIntegrationService.java` (lines 67–74)
* **Exact problem:** `POST /api/v1/repositories/sync` only syncs a single page (`page=1, perPage=100`). Users with >100 repositories never see the rest, and `lastSyncedAt` is set *after* `upsertRepository` (relying on dirty checking rather than explicit save if detached).
* **User/business impact:** Organizations/users with >100 repos cannot access older repositories.
* **Recommended fix:** Paginate through GitHub `/user/repos` (up to a safe cap) during sync.
* **Verification method:** Mock 2 pages of GitHub repos in `GitHubIntegrationServiceTest` and verify all are persisted.

### P2-6: Default Gemini Model `gemini-1.5-pro` & Unbounded Prompt Token Size
* **File/module affected:** `backend/src/main/resources/application.yml` (line 67), `backend/src/main/java/com/codepilot/ai/GeminiCodeReviewProvider.java` (line 40), `backend/src/main/java/com/codepilot/ai/ReviewPromptBuilder.java` (lines 35–46)
* **Exact problem:** Default model is `gemini-1.5-pro` (older/deprecated on `v1beta` for newer projects), and `ReviewPromptBuilder` concatenates all reviewable files up to 2MB (`max-diff-bytes`) into a single prompt without chunking or token-budget truncation, risking 400 payload-too-large or 60s read timeouts.
* **User/business impact:** Large PRs fail with Gemini timeouts or model-not-found errors.
* **Recommended fix:** Default to a current supported model (e.g., `gemini-2.5-flash` or `gemini-2.5-pro`) and chunk/batch files by character/token budget.
* **Verification method:** Unit test `ReviewPromptBuilder` with >200KB diff verifying bounded prompt chunks.

### P2-7: Spring Data JPA/Redis Repository Scanning Ambiguity
* **File/module affected:** `backend/src/main/java/com/codepilot/CodePilotApplication.java`, `backend/src/main/java/com/codepilot/config/RedisConfig.java`
* **Exact problem:** Both `spring-boot-starter-data-jpa` and `spring-boot-starter-data-redis` are on the classpath without `@EnableJpaRepositories` / `@EnableRedisRepositories` scoping, causing Spring Data Redis to scan all 9 JPA repositories at startup and emit warnings.
* **User/business impact:** Noisy startup logs and slower context initialization; risk of repository binding conflicts if Redis repositories are added later.
* **Recommended fix:** Add `@EnableJpaRepositories(basePackages = "com.codepilot.repository")` on a JPA config class and disable Redis repositories (`spring.data.redis.repositories.enabled=false`).
* **Verification method:** Start application/tests and verify `Spring Data Redis - Could not safely identify store assignment` warnings disappear.

---

## 6. Low-Priority Issues (P3)

### P3-1: Dead Code & Unused Services/Components
* **File/module affected:** `backend/src/main/java/com/codepilot/service/MockAiReviewEngine.java`, `backend/src/main/java/com/codepilot/controller/AuthController.java` (`githubCallback`), `backend/src/dto/` (empty directory), root scratch files (`clean_workspace.sh`, `find_files.js`, `fix.js`, `generate_missing.sh`, `run_compile_11.js`, `show*.txt`, `commit_res.txt`, `full_git_log.txt`, `git_status_debug.txt`, `test_find.txt`, `backend/mvn_output*.log`).
* **Exact problem:** Unused `MockAiReviewEngine` `@Service`, unreachable `/api/v1/auth/github/callback` endpoint, empty `backend/src/dto`, and 15+ temporary scripts/logs committed in the repo root.
* **User/business impact:** Confusion for maintainers and bloated repository.
* **Recommended fix:** Remove dead code, empty directories, and scratch scripts/logs; add `*.log` to `.gitignore`.
* **Verification method:** `git status` and `mvn test` after cleanup.

### P3-2: Non-Functional UI Controls & Missing Logout/Auth Redirect
* **File/module affected:** `frontend/src/components/layout/header.tsx` (lines 39–59), `frontend/src/app/page.tsx` (lines 19–21, 37, 89–95), `frontend/src/components/dashboard/recent-reviews.tsx` (lines 21–25)
* **Exact problem:**
  1. Header bell button, "Settings", and "Support" menu items do nothing; no Logout button or user identity is shown.
  2. Landing page uses `<Link href={githubLoginUrl}>` (Next.js client Link) instead of `<a>` for external OAuth redirect, and has dead `#`/placeholder links for Pricing, Terms, and Privacy.
  3. `RecentReviews` has no icon for `PARTIAL` status and displays raw UUID prefixes instead of PR title/number.
* **User/business impact:** Poor UX and dead clicks.
* **Recommended fix:** Use `<a href={githubLoginUrl}>`, wire header dropdown navigation + logout, add `PARTIAL` status icon, and enrich `ReviewDto` with PR number/title and repo name.
* **Verification method:** Manual UI walkthrough and ESLint/Playwright checks.

### P3-3: Next.js Build Warning for Parent `pnpm-lock.yaml`
* **File/module affected:** `frontend/next.config.ts`
* **Exact problem:** `npm run build` warns: `Next.js ignored pnpm-lock.yaml in /Users/kunjmaheshwari because it is outside the current Git repository`.
* **User/business impact:** Build environment ambiguity when parent directories have lockfiles.
* **Recommended fix:** Set `turbopack.root` in `next.config.ts` or ensure clean workspace isolation.
* **Verification method:** `cd frontend && npm run build` without root warning.

---

## 7. Broken and Incomplete User Flows

| Flow | Current State | Where It Breaks |
| :--- | :--- | :--- |
| **1. GitHub OAuth Sign-In (Prod)** | Broken | `SecurityConfig` redirects to `http://localhost:3000/dashboard`; `WebConfig` only allows `localhost:3000`. |
| **2. Sync Repositories** | Broken in Browser | `POST /api/v1/repositories/sync` fails with `403` because `api.ts` omits `X-XSRF-TOKEN` and `SecurityConfig` lacks `.cors()`. |
| **3. Manual PR Review** | Broken | No endpoint/UI syncs PRs to DB (`synchronizePullRequests` is unused); UI shows 0 PRs. |
| **4. Webhook-Triggered Review** | Broken | `GitHubWebhookService.processPendingEvents()` calls `@Modifying claim()` without `@Transactional` -> `TransactionRequiredException`. |
| **5. Async Job Recovery** | Broken | `ReviewJobProducer.recoverPendingJobs()` hits `LazyInitializationException`; `heartbeat()` touches all `PROCESSING` jobs globally. |
| **6. Post Review to GitHub** | Missing | `CodeReviewOrchestrator` never calls GitHub API to post review comments. |
| **7. View Completed Findings** | Broken | `ReviewsPage` only fetches findings when a polled active job finishes, never for already-completed reviews. |
| **8. Repo Review Settings** | Incomplete | `ReviewSettings` entity/table exists but has no API, orchestrator integration, or UI. |
| **9. Sign Out** | Missing | No logout endpoint or UI button. |

---

## 8. Security Findings

1. **Missing CSRF Token in Frontend + Missing `.cors()` in `SecurityConfig` (High):** Causes all browser mutations to fail with `403`, and in production cross-domain setups (`app.domain` vs `api.domain`), `CookieCsrfTokenRepository` without `SameSite=None; Secure` and `Domain` will prevent JS from reading `XSRF-TOKEN`.
2. **Swagger UI & OpenAPI Publicly Exposed (Medium):** `SecurityConfig.java:43-44` permits `/swagger-ui/**`, `/v3/api-docs/**`, and `/swagger-ui.html` for unauthenticated users in all profiles, leaking full API schema in production.
3. **Hardcoded Test Encryption Key in `src/main/resources/application-test.yml` (High):** Packaged in production JAR; activating `test` profile exposes a known AES-256 key and H2 DB.
4. **Global Single Webhook Secret (Medium):** `GitHubWebhookSignatureVerifier` uses one `GITHUB_WEBHOOK_SECRET` for all repositories and fails startup outside `test` profile if unset, rather than supporting GitHub App installation secrets or per-repo secrets.
5. **Prompt Injection Defense (Good, Needs Hardening):** `ReviewPromptBuilder` includes an explicit untrusted-data directive, and `ReviewResponseValidator` validates schema/file/line membership, though `suggestedFix` and `description` are rendered in React as plain text (safe from XSS) — ensure Markdown/code rendering never uses `dangerouslySetInnerHTML`.
6. **Tenant Isolation (Good):** `RepositoryService`, `PullRequestService`, `ReviewService`, and `DashboardService` consistently scope queries by `ownerId` / `getAuthenticatedUser().getId()`.
7. **Token Encryption (Good):** `EncryptionService` uses AES-256-GCM with 12-byte random `SecureRandom` nonce and 128-bit auth tag (`v2:` prefix).

---

## 9. Build and Test Results (Actual Evidence)

### Backend & Frontend (Updated After Phase 5 Security Hardening)
* **`cd backend && mvn clean test -B && mvn verify -B`:** **PASSED** (exit code 0; `Tests run: 77, Failures: 0, Errors: 0, Skipped: 0`).
  * **Unit tests (57):** Existing 50 + 3 `StartupConfigurationValidatorTest` + 3 `ReviewJobWorkerTest` + 1 `ReviewJobServiceTest`.
  * **H2/Spring Boot integration & security tests (11):** `CodePilotApplicationTests` (1), `AiReviewEngineIntegrationTest` (3), `RepositoryControllerTest` (1), `SecurityHardeningIntegrationTest` (6: CSRF cookie issuance, CSRF enforcement on `DELETE`, CORS allow/reject, `/api/v1/auth/me` secret-free response & cross-tenant `404`, logout session invalidation & cookie clearing, webhook HMAC without CSRF & Swagger restriction).
  * **PostgreSQL 17 + Redis 7 Testcontainers tests (9):** `PostgresRedisInfrastructureIntegrationTest` (4) + `AsyncReliabilityIntegrationTest` (5).
* **`cd frontend && npm run lint && npx tsc --noEmit && npm run build`:** **PASSED** (exit code 0; ESLint 0 errors, TypeScript 0 errors, Next.js 16.4.5 production build succeeded).
* **Remaining Security Risks (Phase 6+):**
  * `RateLimitFilter` is in-memory per instance (sufficient for single-node; multi-instance deployments should back it with Redis).
  * In cross-subdomain production deployments (`app.example.com` + `api.example.com`), `COOKIE_SECURE=true` and `COOKIE_SAME_SITE=None` must be set in environment variables (and `CookieCsrfTokenRepository` domain configured if subdomains differ).
  * `P0-5` (PR sync endpoint/UI), `P1-5` (GitHub PR review comment posting), `P1-6` (`ReviewsPage` findings), `P1-8` (`ReviewResponseValidator`), and Docker/CI/CD remain for Phase 6.

### Frontend
* **Lint & Production Build (`cd frontend && npm run lint && npm run build`):** **PASSED** (exit code 0)
  * ESLint passed with 0 errors.
  * `next build` (Next.js 16.4.5 Turbopack) compiled TypeScript and generated 8 static routes (`/`, `/_not-found`, `/dashboard`, `/dashboard/repositories`, `/dashboard/reviews`, `/dashboard/settings`).
* **Frontend Unit / Integration / E2E Tests:** **NONE** (`package.json` has no `test` script; no Jest, Vitest, RTL, or Playwright tests exist).

---

## 10. Dependency and Configuration Problems

| Component | Problem |
| :--- | :--- |
| `backend/pom.xml` | `<java.version>25</java.version>` incompatible with JDK 23 and Spring Boot 3.2.3. |
| `backend/src/main/resources/application.yml` | Requires `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET`, `ENCRYPTION_KEY`, `GEMINI_API_KEY`, `GITHUB_WEBHOOK_SECRET` with no `.env.example` documenting them; `baseline-on-migrate: true` can mask unversioned DBs. |
| `backend/src/main/resources/application-test.yml` | Located in `src/main/resources` instead of `src/test/resources`. |
| `frontend/package.json` | Lists `"cn": "^0.5.2"` and `"shadcn": "^4.23.0"` in runtime `dependencies`; no test runner configured. |
| Docker / CI/CD | **Missing:** No `Dockerfile` (backend or frontend), no `docker-compose.yml` (Postgres + Redis + Backend + Frontend), no GitHub Actions CI workflow (`.github/` only contains `.github/modernize/java-upgrade/` hooks). |

---

## 11. Recommended Remediation Sequence

1. **Unblock Build & Config (Day 1):** Fix `java.version` to `21` in `pom.xml`; move `application-test.yml` to `src/test/resources`; add root `.env.example` and `docker-compose.yml` (Postgres 16 + Redis 7).
2. **Fix Security, CORS & CSRF (Day 1–2):** Add `.cors(...)` + configurable `FRONTEND_URL` / `CORS_ALLOWED_ORIGINS` in `SecurityConfig` and `WebConfig`; send `X-XSRF-TOKEN` in `frontend/src/lib/api.ts`; resolve OAuth user by `githubUserId`; restrict Swagger UI to non-prod profiles.
3. **Fix Webhook & Async Worker Reliability (Day 2–3):** Add `@Transactional` to `WebhookDeliveryRepository.claim(...)`; reload `WebhookDelivery` after `claim` and remove self-invocation `@Transactional`; add `@EntityGraph` to `findPendingJobs`; scope `touchProcessingJobs` to active worker jobs; add `ThreadPoolTaskScheduler` and remove `Thread.sleep` from `poll()`.
4. **Complete Core Review Flows (Day 3–5):** Add `POST /api/v1/repositories/{id}/pull-requests/sync` + UI trigger; implement GitHub PR review comment posting in `GitHubApiClient`/`CodeReviewOrchestrator`; fix `ReviewsPage` to load findings for completed reviews; harden `ReviewResponseValidator` against off-by-one lines and long titles; update default Gemini model.
5. **Testing & Deployment Readiness (Day 5–7):** Add Testcontainers (PostgreSQL + Redis) integration tests for Flyway, `ReviewJobWorker`, and `GitHubWebhookService`; add Playwright/Vitest frontend tests; add `Dockerfile`s and GitHub Actions CI (`mvn test`, `npm run lint && npm run build`).

---

## 12. Final Production-Readiness Score

**Score: 32 / 100 (Not Production-Ready)**

| Category | Score | Justification |
| :--- | :---: | :--- |
| Build & Dependencies | 3/10 | Clean backend build fails (`java.version=25`); test config ships in main resources. |
| Database & Migrations | 7/10 | Clean Flyway V1–V6 schema and indexes, but untested against real Postgres in CI. |
| Queue, Workers & Recovery | 3/10 | Good Redis RPOPLPUSH pattern on paper, broken by `LazyInitializationException`, global heartbeat, and single-thread `Thread.sleep`. |
| GitHub & Webhooks | 3/10 | Webhook crashes with `TransactionRequiredException`; no PR sync endpoint; no PR comment posting. |
| Gemini AI Integration | 6/10 | Good prompt-injection defense and schema validation, but brittle on single-line off-by-one and outdated default model. |
| Security & Auth | 4/10 | Good AES-GCM encryption and tenant isolation, but broken CSRF/CORS, hardcoded `localhost:3000`, public Swagger, email-based identity. |
| Frontend UX & Integration | 3/10 | Clean UI shell, but mutations fail 403, PRs don't sync, completed findings aren't fetched. |
| Tests & CI/CD | 3/10 | 55 H2/mocked backend tests; 0 frontend tests; 0 E2E tests; no Docker or CI/CD. |


---

## 13. Phase 6 Remediation Update (GitHub Repository Sync, PR Sync & Manual Review Workflow)

### Completed Changes

1. **GitHub API Reliability (`GitHubApiClient.java`)**:
   - Added explicit `409 Conflict` and `422 Unprocessable Entity` classification in `classify(...)`.
   - Classified `403` responses accompanied by rate-limit retry headers (`Retry-After` / `X-RateLimit-Reset`) as `"GitHub API rate limit exceeded"` while preserving bounded retries (`MAX_ATTEMPTS = 3`, `MAX_RETRY_WAIT_SECONDS = 5`).
   - Preserved token redaction and omitted diff payloads from logs.

2. **Repository & Pull Request Synchronization (`GitHubIntegrationService.java`, `RepositoryRepository.java`, `RepositoryController.java`)**:
   - Added `synchronizeAllRepositories(User)` with bounded pagination (up to 10 pages × 100 repositories) keyed by immutable `githubRepoId`, tracking `archived`/`disabled` state into `isActive`, and deactivating missing repositories only after full pagination completes.
   - Added `synchronizeAllPullRequests(User, Repository)` with bounded pagination across `state=all`, mapping merged pull requests (`merged_at != null` or `merged == true`) to `"merged"`.
   - Exposed `POST /api/v1/repositories/{id}/pull-requests/sync` (enforcing repository ownership via `repositoryService.getOwnedRepository(id)`).
   - Validated `owner/repo` `fullName` safely in `splitFullName(...)` across PR list, details, and diff calls.

3. **Manual Review Workflow (`ReviewService.java`, `ReviewDto.java`)**:
   - Rejected manual review requests on inactive/archived repositories or non-open (`closed`/`merged`) pull requests with `IllegalArgumentException` (`400 Bad Request`).
   - Preserved active-review and `headSha` deduplication and populated `jobId` in `ReviewDto` for consistent review/job tracking.

4. **Frontend Integration (`frontend/src/lib/api.ts`, `frontend/src/app/dashboard/repositories/page.tsx`, `frontend/src/app/dashboard/reviews/page.tsx`)**:
   - Added `api.syncPullRequests(repositoryId)` and `Review.jobId`.
   - Added loading/submitting states to prevent duplicate repository sync, PR sync, and review submissions; disabled `"Review"` on non-open PRs or inactive repositories; added status banner with link to `/dashboard/reviews`.
   - Added `"Load Findings"` action on completed/partial reviews in `ReviewsPage`.

### Verification Evidence

- **Backend (`cd backend && mvn clean test -B && mvn verify -B`)**: **83 tests run, 0 failures, 0 errors, 0 skipped** (including `GitHubApiClientTest`, `GitHubIntegrationServiceTest`, and `ManualReviewWorkflowIntegrationTest` against Testcontainers `postgres:17-alpine` + `redis:7-alpine`).
- **Frontend (`cd frontend && npm run lint && npx tsc --noEmit && npm run build`)**: Passed with 0 errors.

### Remaining Limitations (Deferred to Subsequent Phases)

- Gemini AI review prompt hardening, response validation, and GitHub PR review comment posting are not yet addressed (Phase 7+).
- Dockerfiles, `docker-compose.yml`, and CI/CD workflows remain to be added.

---

## 14. Phase 7 Remediation Update (Gemini AI Review Engine Hardening & GitHub PR Review Comments)

### Implemented Functionality

1. **Gemini AI Review Engine Hardening (`GeminiCodeReviewProvider.java`, `ReviewPromptBuilder.java`, `ReviewResponseValidator.java`, `application.yml`)**:
   - Separated trusted system directives (`systemInstruction`) from untrusted repository diff payloads (`contents` wrapped in `<untrusted_repository_diff>` tags with closing-tag escaping) in Gemini requests.
   - Enforced strict structured JSON output via `generationConfig.responseMimeType = "application/json"`, `temperature = 0.1`, and an explicit `responseSchema` matching the finding object contract.
   - Updated default model configuration to `gemini-2.5-flash` (`GEMINI_API_MODEL`).
   - Implemented bounded retries (`MAX_ATTEMPTS = 3`) with `Retry-After` support and exponential backoff + jitter for transient `429`, `5xx`, and `ResourceAccessException` failures; permanent `4xx` errors (`400`/`401`/`403`/`404`/`422`) fail immediately without retrying.
   - Handled empty bodies, safety-blocked responses (`promptFeedback.blockReason`), malformed JSON, non-object entries, invalid `filePath` values (`..` traversal, leading `/`, `\`, null bytes), invalid enums, non-added lines, >255-character titles, and whitespace-normalized duplicate findings.
   - Added structured logs and atomic counters for Gemini successes, failures, retries, latency, validation failures, and findings generated without logging API keys, tokens, or raw diffs.

2. **GitHub PR Review Comment Publishing & Idempotency (`V7__add_github_comment_publications.sql`, `GitHubCommentPublication.java`, `GitHubCommentPublicationRepository.java`, `GitHubApiClient.java`, `GitHubReviewPublisher.java`, `CodeReviewOrchestrator.java`)**:
   - Added explicit feature flag `codepilot.review.publish-comments-enabled` (`CODEPILOT_REVIEW_PUBLISH_COMMENTS_ENABLED`), **disabled (`false`) by default** for human-in-the-loop safety. CodePilot remains strictly read-only with respect to repository source code (no commits, pushes, merges, or AutoFix).
   - Added `POST /repos/{owner}/{repo}/pulls/{prNumber}/comments` (inline review comments with `commit_id`, `path`, `line`, `side=RIGHT`) and `POST /repos/{owner}/{repo}/issues/{prNumber}/comments` (PR-level summary fallback) plus `GET` reconciliation helpers in `GitHubApiClient`.
   - `GitHubApiClient.postComment` does **not** blindly retry `POST` mutations on `ResourceAccessException` timeouts; instead, `GitHubReviewPublisher` records `AMBIGUOUS_TIMEOUT` in `github_comment_publications` and reconciles against existing GitHub comments (via deterministic `<!-- codepilot:idempotency:<sha256> -->` markers) before retrying.
   - Falls back to a concise PR-level summary comment when inline diff positioning is unavailable or GitHub returns `422 Unprocessable Entity`.
   - Enforces repository/owner/review tenant boundaries and skips unvalidated findings.

### Files Changed / Added
- **Main Source & Config**:
  - `backend/src/main/java/com/codepilot/ai/ReviewPromptBuilder.java`
  - `backend/src/main/java/com/codepilot/ai/ReviewResponseValidator.java`
  - `backend/src/main/java/com/codepilot/ai/GeminiCodeReviewProvider.java`
  - `backend/src/main/java/com/codepilot/service/GitHubApiClient.java`
  - `backend/src/main/java/com/codepilot/service/GitHubReviewPublisher.java` (new)
  - `backend/src/main/java/com/codepilot/service/CodeReviewOrchestrator.java`
  - `backend/src/main/java/com/codepilot/model/GitHubCommentPublication.java` (new)
  - `backend/src/main/java/com/codepilot/repository/GitHubCommentPublicationRepository.java` (new)
  - `backend/src/main/resources/application.yml`
  - `backend/src/main/resources/db/migration/V7__add_github_comment_publications.sql` (new)
- **Tests**:
  - `backend/src/test/java/com/codepilot/ai/ReviewPromptBuilderTest.java` (new)
  - `backend/src/test/java/com/codepilot/ai/GeminiCodeReviewProviderTest.java` (new)
  - `backend/src/test/java/com/codepilot/ai/ReviewResponseValidatorTest.java`
  - `backend/src/test/java/com/codepilot/ai/CodeReviewOrchestratorTest.java`
  - `backend/src/test/java/com/codepilot/service/GitHubApiClientTest.java`
  - `backend/src/test/java/com/codepilot/service/GitHubReviewPublisherTest.java` (new)
  - `backend/src/test/java/com/codepilot/integration/GitHubCommentPublicationIntegrationTest.java` (new)
  - `backend/src/test/java/com/codepilot/infrastructure/PostgresRedisInfrastructureIntegrationTest.java`

### Verification Results
- **Backend (`cd backend && mvn clean test -B && mvn verify -B`)**: **100 tests run, 0 failures, 0 errors, 0 skipped** (verified against Testcontainers `postgres:17-alpine` + `redis:7-alpine`, Flyway V1–V7, and `MockRestServiceServer` / mocked GitHub & Gemini HTTP responses).
- **Production JAR Check**: Verified `backend/target/backend-0.0.1-SNAPSHOT.jar` excludes `application-test.yml` and `docker-java.properties`.
- **Frontend**: No frontend files were modified in Phase 7.

### Security Considerations & Remaining Limitations
- GitHub comment publishing requires `CODEPILOT_REVIEW_PUBLISH_COMMENTS_ENABLED=true` to activate in production; it remains `false` by default.
- Live end-to-end calls against external `generativelanguage.googleapis.com` and `api.github.com` are mocked in automated tests and were not executed against live third-party endpoints.
- Dockerfiles, `docker-compose.yml`, and CI/CD workflows remain for Phase 8+.

### Updated Production-Readiness Assessment
- **AI Review Engine & GitHub Comment Publishing**: Hardened for structured output, prompt-injection isolation, bounded retries, idempotent comment publication, and timeout reconciliation.
- **Overall Status**: Core backend build, database migrations (V1–V7), Redis async worker, OAuth/CSRF/tenant security, repo/PR sync, manual review flow, and AI/GitHub publishing are verified; containerization and CI/CD remain for Phase 8.

---

## 15. Phase 8 Remediation Update (Complete Frontend Redesign & Experience)

### Redesigned Pages & Layouts
- **Landing Page (`frontend/src/app/page.tsx`)**: Replaced generic marketing copy and dead links with a restrained, developer-focused landing page featuring CodePilot's real architecture, an example diff-anchored finding preview, a 3-step engineering workflow, security/tenant-isolation guarantees, and direct GitHub OAuth sign-in.
- **Workspace Shell (`frontend/src/app/dashboard/layout.tsx`, `loading.tsx`, `error.tsx`, `sidebar.tsx`, `header.tsx`)**: Unified desktop sidebar and mobile `Sheet` navigation, added route-aware header titles, fetched authenticated user profile (`api.getMe()`) for the account menu, removed dead notification icons, and added matching skeleton/error recovery views.
- **Main Dashboard (`frontend/src/components/dashboard/dashboard-client.tsx`, `stats.tsx`, `activity-chart.tsx`, `recent-reviews.tsx`)**: Redesigned KPI tiles with tabular monospace figures, fixed responsive `lg:col-span-7` / `lg:col-span-5` layout, added empty state for 7-day activity, and linked recent reviews directly to `/dashboard/reviews?reviewId=...`.
- **Repositories & Pull Requests (`frontend/src/app/dashboard/repositories/page.tsx`)**: Implemented a master-detail workspace with repository search, active/inactive filtering, PR status filtering (`all`/`open`/`closed`/`merged`), duplicate-click prevention, disabled states for non-open PRs or inactive repositories, and direct links to inspect newly queued reviews.
- **Reviews & Findings (`frontend/src/app/dashboard/reviews/page.tsx`)**: Built a split-pane code-review workspace with review status filtering, ID search, automatic 5s job polling, automatic findings loading for `COMPLETED`/`PARTIAL` reviews, clickable severity breakdown pills (`CRITICAL`, `HIGH`, `MEDIUM`, `LOW`, `INFO`), `Previous`/`Next` finding navigation, and structured evidence/impact/suggested-fix blocks.
- **Settings (`frontend/src/app/dashboard/settings/page.tsx`)**: Displays authenticated account metadata (`username`, `email`), GitHub connection status, a confirmation `Dialog` before disconnecting GitHub (`api.disconnectGitHub()`), and explicit `Sign out` (`api.logout()`).

### Design System Components Added
- `frontend/src/app/globals.css` & `frontend/src/app/layout.tsx`: Added `Inter` (`--font-inter`) and `JetBrains_Mono` (`--font-jetbrains-mono`), refined neutral slate surface/border tokens, and wired `ToastProvider`.
- `frontend/src/components/ui/input.tsx`: Styled text input primitive.
- `frontend/src/components/ui/skeleton.tsx`: Loading skeleton primitive.
- `frontend/src/components/ui/status-badge.tsx`: Semantic status and severity badge component.
- `frontend/src/components/ui/toast.tsx`: Accessible non-blocking toast notification provider (`useToast`).
- `frontend/src/components/ui/empty-state.tsx`: Shared `EmptyState` and retryable `ErrorBanner` components.

### Verification Results
- **`cd frontend && npm run lint`**: Passed (0 errors, 0 warnings).
- **`cd frontend && npx tsc --noEmit`**: Passed (0 TypeScript errors).
- **`cd frontend && npm run build`**: Passed (`next build` compiled and generated all routes `/`, `/dashboard`, `/dashboard/repositories`, `/dashboard/reviews`, `/dashboard/settings`).
- **Backend**: Unchanged in Phase 8 (100/100 backend unit + Testcontainers integration tests passed at the end of Phase 7).

### Remaining Frontend Limitations
- No automated frontend unit or Playwright E2E test suite was configured in `frontend/package.json` in Phase 8; resolved in Phase 9 below.
- Live browser walkthroughs against a running backend + GitHub OAuth app require runtime OAuth credentials.

---

## 16. Phase 9 Remediation Update (End-to-End Testing, Integration Testing & Quality Assurance)

### Testing Architecture Overview
- **Frontend E2E Suite (`frontend/e2e/` + `frontend/playwright.config.ts`)**:
  - Installed `@playwright/test` and Chromium (`npm run test:e2e` script added to `frontend/package.json`).
  - Configured Playwright against the production Next.js server (`npm run start -- -p 3000`) with deterministic API route interception in `frontend/e2e/fixtures.ts` (`setupAuthenticatedApi`, `consoleErrors` assertion fixture).
  - Test specs:
    - `frontend/e2e/landing-auth.spec.ts`: Landing page rendering, GitHub OAuth login entry point, and unauthenticated redirect/session-expiry handling.
    - `frontend/e2e/dashboard-repos.spec.ts`: Authenticated dashboard metrics, 7-day activity chart, repository search & active/inactive filtering, PR sync, and manual review submission with duplicate-click protection.
    - `frontend/e2e/reviews-settings.spec.ts`: Split-pane review workspace, live job polling transition (`QUEUED` -> `COMPLETED`), severity breakdown filtering, finding navigation (`Previous`/`Next`), settings account metadata, GitHub disconnect confirmation dialog, and sign-out flow.
    - `frontend/e2e/responsive-a11y.spec.ts`: Mobile viewport (`390x844`) responsive sidebar `Sheet` drawer and accessibility landmark/keyboard checks.
- **Backend Full-Stack Integration Suite (`backend/src/test/java/com/codepilot/integration/FullReviewJourneyIntegrationTest.java`)**:
  - Runs against real Testcontainers (`postgres:17-alpine` with Flyway `V1`–`V7` migrations + `redis:7-alpine`) with `@AutoConfigureMockMvc`, `@MockBean GitHubApiClient`, and `MockRestServiceServer` for Gemini API responses.
  - Verifies the complete end-to-end journey: authenticated repo sync -> PR sync -> manual review submission -> Redis job queuing -> `ReviewJobWorker.poll()` execution -> Gemini structured JSON parsing -> PostgreSQL findings persistence -> idempotent `GitHubCommentPublication` (`PUBLISHED`) -> `GET /api/v1/reviews/{id}/findings` -> cross-tenant isolation (`404`/`403` for non-owners) -> token redaction in API responses.
  - Verifies concurrent worker safety (`concurrentWorkersClaimReviewJobAtMostOnce`): two threads racing to claim the same queued review job result in at most one owner claiming and completing the job.

### Verification Results
- **Frontend (`cd frontend && npm run lint && npx tsc --noEmit && npm run build && npx playwright test`)**:
  - `npm run lint`: Passed (0 errors).
  - `npx tsc --noEmit`: Passed (0 errors).
  - `npm run build`: Passed (compiled `/`, `/dashboard`, `/dashboard/repositories`, `/dashboard/reviews`, `/dashboard/settings`).
  - `npx playwright test`: **13 passed (8.1s)** across all 4 spec files.
- **Backend (`cd backend && mvn clean test -B && mvn verify -B`)**:
  - **102 tests run, 0 failures, 0 errors, 0 skipped** (`BUILD SUCCESS`).
  - Production JAR check (`jar tf backend/target/backend-0.0.1-SNAPSHOT.jar | grep -E "application-test|docker-java"`): Clean — no test configuration or test dependencies packaged in the production JAR.

### Known Gaps & Remaining Limitations
- Playwright E2E tests stub backend `/api/v1/*` HTTP responses via `page.route` rather than running against a live Spring Boot + GitHub OAuth app instance (external GitHub OAuth credentials are not present in local/CI test environments).
- Dockerfiles and CI/CD pipeline definitions (GitHub Actions) are not yet added (scheduled for Phase 10).

### Updated Production-Readiness Assessment
- **Build & Packaging**: Ready (Java 21 LTS target, clean production JAR).
- **Database, Migrations & Queue**: Ready (PostgreSQL 17 + Flyway V1–V7 + Redis 7 ZSET retries, atomic job claiming, and Testcontainers verification).
- **Security & Tenant Isolation**: Ready (OAuth2 session cookies, XSRF token handling, CORS allowlist, cross-tenant isolation verified).
- **AI Review & GitHub Publishing**: Ready (strict Gemini JSON schema + untrusted diff delimiters + idempotent GitHub PR comment publication gated behind `codepilot.review.publish-comments-enabled`).
- **Frontend & QA**: Ready (complete UI redesign + 13 Playwright E2E tests + 102 backend unit/integration tests passing). Containerization and CI/CD automation completed in Phase 10 below.

---

## 17. Phase 10 Remediation Update (Docker, CI/CD, Containerization & Deployment Readiness)

### Containerization & Compose Architecture
- **Backend (`backend/Dockerfile`, `backend/.dockerignore`)**: Multi-stage build using `maven:3.9.9-eclipse-temurin-21-alpine` -> `eclipse-temurin:21-jre-alpine`, non-root runtime user `codepilot`, container-aware JVM flags (`-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC`), graceful shutdown (`server.shutdown: graceful`), and `/actuator/health/readiness` health check.
- **Frontend (`frontend/Dockerfile`, `frontend/.dockerignore`, `frontend/next.config.ts`)**: Enabled Next.js `output: "standalone"`, multi-stage `node:22-alpine` build (`npm ci` -> `npm run build` -> minimal runtime), non-root runtime user `nextjs`, and `/api/health` health check (`frontend/src/app/api/health/route.ts`).
- **Docker Compose (`docker-compose.yml`)**: Runs `postgres:17-alpine`, `redis:7-alpine` (`--appendonly yes`), `backend`, and `frontend` on an isolated `codepilot-network` bridge with named persistent volumes (`postgres_data`, `redis_data`), `no-new-privileges:true`, `service_healthy` dependency ordering, configurable `BACKEND_HOST_PORT` / `FRONTEND_HOST_PORT`, and no public host ports on PostgreSQL or Redis (leaving local EnterpriseDB 5432 and Homebrew Redis untouched).

### Health Checks, Environment Configuration & Documentation
- Added `spring-boot-starter-actuator` (`backend/pom.xml`) and configured liveness/readiness probes (`management.endpoint.health.probes.enabled: true`, `show-details: ${ACTUATOR_HEALTH_SHOW_DETAILS:never}`) in `backend/src/main/resources/application.yml`.
- Permitted `/actuator/health`, `/actuator/health/liveness`, and `/actuator/health/readiness` in `SecurityConfig.java`; added `StartupLifecycleLogger.java` for structured startup/shutdown logging without secret exposure.
- Added `.env.example`, `backend/.env.example`, updated `.gitignore` for `.env.*` and Playwright artifacts, and created `docs/DOCKER_SETUP.md`, `docs/ENVIRONMENT_VARIABLES.md`, and `docs/DEPLOYMENT_GUIDE.md`.

### CI/CD Workflows (`.github/workflows/`)
- **`.github/workflows/ci.yml`**: Pull Request & `main` CI with `contents: read` least privilege, running `backend-test` (`mvn clean test -B && mvn verify -B`), `frontend-test` (`npm ci`, `npm run lint`, `npx tsc --noEmit`, `npm run build`, Playwright Chromium E2E), and `docker-validation` (`docker compose --env-file .env.example config -q`, image builds without pushing, advisory Trivy scans).
- **`.github/workflows/deploy.yml`**: Release/manual-dispatch deployment workflow (`release: [published]` or `workflow_dispatch`) using GitHub `environment` protection gates, secret presence validation, and a configurable deployment hook.

### Verification Results
- **Docker Compose & Image Builds**: `docker compose --env-file .env.example config -q` valid; `codepilot-verify-backend` and `codepilot-verify-frontend` built cleanly.
- **Containerized Runtime (`codepilot-verify` on ports `18080`/`13000`)**:
  - All 4 containers (`postgres`, `redis`, `backend`, `frontend`) reached `healthy` status.
  - Flyway migrations `V1`–`V7` succeeded (`7 rows` in `flyway_schema_history`).
  - `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` returned `UP`; `/api/health` returned `{"status":"ok"}`; `GET /api/v1/auth/csrf` returned `204` with `Access-Control-Allow-Origin: http://localhost:13000` and `XSRF-TOKEN` cookie.
  - Non-root users verified (`codepilot` in backend, `nextjs` in frontend).
  - Data persistence verified across `docker compose down` -> `up -d --wait` for both PostgreSQL and Redis; disposable volumes cleaned up with `down -v`.
- **Workflow YAML Validation**: `.github/workflows/ci.yml` and `.github/workflows/deploy.yml` parsed cleanly with Ruby YAML loader.
- **Backend (`cd backend && mvn clean test -B && mvn verify -B`)**: **Tests run: 102, Failures: 0, Errors: 0, Skipped: 0** (`BUILD SUCCESS`).
- **Frontend (`cd frontend && npm run lint && npx tsc --noEmit && npm run build && npx playwright test`)**: ESLint 0 errors, `tsc` 0 errors, Next.js standalone build succeeded, **13 passed (8.4s)**.

### Remaining Deployment Limitations
- `NEXT_PUBLIC_API_BASE_URL` is inlined at Next.js image build time; staging and production deployments must build separate frontend images (or pass the environment-specific `NEXT_PUBLIC_API_BASE_URL` build arg in `deploy.yml`).
- Trivy vulnerability scanning in `ci.yml` runs in advisory mode (`exit-code: "0"`, `ignore-unfixed: true`) and has not been executed locally inside GitHub Actions yet.
- `.github/workflows/deploy.yml` contains a configurable deployment hook placeholder and requires selecting a concrete target runtime (e.g., ECS, Cloud Run, Kubernetes, or VM Compose) and populating GitHub Environment secrets before production rollout.

---

## Final Production Readiness Assessment — Phase 11

### 1. Overall Readiness Score
**88 / 100** — **Ready for single-instance staging/pilot deployment** after provisioning external secrets and TLS termination. Multi-instance horizontal scaling and live external OAuth/Gemini/GitHub soak validation remain pre-launch operational gates (see Remaining Issues below).

### 2. Security Assessment
- **Authentication, Sessions & Reverse-Proxy OAuth (`SecurityConfig.java`, `AuthController.java`, `application.yml`)**:
  - **Fix applied in Phase 11**: Added `server.forward-headers-strategy: ${FORWARD_HEADERS_STRATEGY:framework}` to `application.yml` (and `application-test.yml`) so `X-Forwarded-Proto` / `X-Forwarded-Host` from a TLS-terminating reverse proxy produce `https://` OAuth2 `redirect_uri` values instead of `http://`. Verified both in `SecurityHardeningIntegrationTest.oauthAuthorizationHonorsForwardedHttpsHeadersBehindReverseProxy` and against the live containerized backend (`Location: ...&redirect_uri=https://api.codepilot.example/login/oauth2/code/github`).
  - Session fixation (`migrateSession()`), `HttpOnly` session cookies, `SameSite` + `COOKIE_SECURE`, CSRF (`CookieCsrfTokenRepository.withHttpOnlyFalse()` + `X-XSRF-TOKEN`), explicit CORS allowlist (no `*` wildcards), `frameOptions.deny()`, `Referrer-Policy`, and full session invalidation on `DELETE /api/v1/auth/github` and `POST /api/v1/auth/logout` are implemented and verified in `SecurityHardeningIntegrationTest`.
- **Multi-Tenant Isolation**:
  - Verified across `RepositoryService`, `PullRequestService`, `ReviewService`, and `DashboardService`: all queries scope by `user.getId()` (`findByIdAndOwnerId`, `findByIdAndRepositoryOwnerId`, `findByIdAndPullRequestRepositoryOwnerId`). Verified in `FullReviewJourneyIntegrationTest` and `SecurityHardeningIntegrationTest`.
- **Secrets & Encryption**:
  - `EncryptionService` uses `AES/GCM/NoPadding` with a 12-byte random nonce, 128-bit tag, and externally configured 256-bit hex `ENCRYPTION_KEY` (validated at startup). `StartupConfigurationValidator` and `GitHubWebhookSignatureVerifier` fail fast outside `test` profile when required secrets are missing.
- **Webhook & API Security**:
  - `GitHubWebhookSignatureVerifier` uses `HmacSHA256` with `MessageDigest.isEqual` constant-time comparison; `GitHubWebhookController` enforces `max-payload-bytes` (1 MB) and returns `401` on invalid signatures; `GlobalExceptionHandler` maps unexpected errors to generic `500` messages without stack traces.
  - `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` are permitted with `show-details: never`; Swagger/OpenAPI is disabled publicly by default (`CODEPILOT_SWAGGER_PUBLIC=false`).
  - **Known Limitation (P2)**: `RateLimitFilter` uses an in-memory `ConcurrentHashMap` (single-instance only) and reads `X-Forwarded-For` directly.

### 3. Backend Reliability Assessment
- `ReviewJobWorker` claims jobs atomically in PostgreSQL (`reviewJobService.claim`), uses Redis `rightPopAndLeftPush` + ZSET delayed retries (`computeRetryDelayMillis`, bounded by `maxAttempts=3`) + DLQ (`codepilot:review-jobs:dead-letter`), and avoids `Thread.sleep()`.
- `ReviewService.createReview` deduplicates active (`QUEUED`, `PROCESSING`, `PARTIAL`) and completed reviews for the same `headSha`, and registers `TransactionSynchronization.afterCommit` before publishing to Redis.
- Graceful shutdown (`server.shutdown: graceful`, 30s timeout) and `StartupLifecycleLogger` verified in tests and containers.

### 4. AI Review Quality & Safety Assessment
- `GeminiCodeReviewProvider` enforces structured JSON output via `responseSchema`, separates trusted instructions from `<untrusted_repository_diff>` delimiters, and bounds retries (`MAX_ATTEMPTS=3`, skipping retry on permanent `401`).
- `ReviewResponseValidator` and `DiffParser` validate file paths, line numbers, severities, and diff size limits (`max-file-bytes`, `max-diff-bytes`) before persistence.
- `GitHubReviewPublisher` enforces idempotency via `<!-- codepilot:idempotency:... -->` markers + `github_comment_publications` table (`V7`), falls back safely on invalid diff positions, and is gated behind `codepilot.review.publish-comments-enabled=false` by default.

### 5. Frontend Assessment
- Next.js 16.4.5 (`output: "standalone"`) with `Inter` / `JetBrains Mono`, `XSRF-TOKEN` auto-bootstrap in `frontend/src/lib/api.ts`, 15s request abort timeout, `EmptyState` / `ErrorBanner`, and `/api/health` readiness endpoint.
- Production build, ESLint, TypeScript check, and 13 Playwright E2E tests pass.

### 6. Database Assessment
- Flyway migrations `V1`–`V7` execute cleanly against PostgreSQL 17 (`7 rows` in `flyway_schema_history`, all `success = t`); Hibernate validates schema (`ddl-auto: validate`) with HikariCP (`max=10`, `min-idle=2`).
- Tenant ownership foreign keys and indexes are in place; no destructive DDL settings exist.

### 7. Infrastructure & Container Security Assessment
- Multi-stage Dockerfiles (`backend/Dockerfile`, `frontend/Dockerfile`) run as non-root users (`codepilot`, `nextjs`), exclude test/dev artifacts, and configure `HEALTHCHECK` probes.
- `docker-compose.yml` isolates `postgres:17-alpine` and `redis:7-alpine` on `codepilot-network` without exposing DB/Redis host ports, sets `no-new-privileges:true`, and uses named persistent volumes (`postgres_data`, `redis_data`).
- **Limitation**: `trivy` is not installed in the local environment, so local container vulnerability scanning was not executed (configured as advisory Trivy jobs in `.github/workflows/ci.yml`).

### 8. CI/CD Assessment
- `.github/workflows/ci.yml` runs backend (`mvn clean test -B`, `mvn verify -B`), frontend (`npm ci`, `npm run lint`, `npx tsc --noEmit`, `npm run build`, Playwright Chromium E2E), and Docker/Compose validation + advisory Trivy scans under `contents: read` least privilege.
- `.github/workflows/deploy.yml` gates releases behind `release: [published]` / `workflow_dispatch` and GitHub Environments (`staging`/`production`).

### 9. Testing Assessment & Evidence Matrix
- **Verified in automated tests**:
  - Backend: `mvn clean test -B && mvn verify -B` -> **128 tests run, 0 failures, 0 errors, 0 skipped** (`BUILD SUCCESS`) using Testcontainers (`postgres:17-alpine`, `redis:7-alpine`).
  - Frontend: `npm run lint` (0 errors), `npx tsc --noEmit` (0 errors), `npm run build` (standalone build succeeded), `npx playwright test` (**14 passed in 7.9s** against the rebuilt Docker Compose `frontend` container).
- **Verified locally (containerized)**:
  - `docker compose --env-file .env.example config -q` valid; `backend` and `frontend` images built cleanly.
  - Isolated `codepilot-verify` Compose stack (`18080`/`13000`): all 4 containers `healthy`, Flyway `V1`–`V7` applied, `/actuator/health/readiness` (`UP`), `/api/health` (`ok`), CORS + `XSRF-TOKEN`, `X-Forwarded-Proto: https` OAuth `redirect_uri` rewrite, non-root users (`codepilot`, `nextjs`), and PostgreSQL/Redis volume persistence across `down` -> `up` restart.
- **Verified using mocked external services**:
  - GitHub OAuth2 login/API calls (`@MockBean GitHubApiClient`, `oauth2Login()`), Gemini API (`MockRestServiceServer`), and Playwright API route interception (`page.route`).
  - Complete pagination for GitHub repository synchronization: Link header parsing (`rel="next"`), multi-page fetching (>250 repositories across 3 pages), empty final pages, cross-page deduplication by immutable GitHub repo ID, tenant isolation, and atomic failure recovery without deactivating existing active repositories.
- **Verified against live external services**:
  - None (no live GitHub OAuth app, live GitHub webhook delivery, or live Gemini API key calls were executed in this audit).
- **Not yet verified**:
  - GitHub Actions workflow execution on GitHub runners (including Trivy container scans), multi-instance horizontal scaling under load, and live TLS reverse-proxy + GitHub OAuth callback end-to-end.

---

## 13. GitHub Repository Synchronization Pagination Implementation

### 13.1 Background & Root Cause
Prior to this fix, `GitHubIntegrationService.synchronizeAllRepositories` suffered from two limitations:
1. It hardcoded a 10-page limit (`MAX_SYNC_PAGES = 10`), but more critically it did not inspect GitHub's REST API `Link` response header (`<...>; rel="next"`).
2. Synchronization fetched repositories without following the standard RFC 5988 / GitHub Link header pagination protocol, risking premature termination or omission of repositories located beyond initial pages (such as `KunjMaheshwari/LeetCode`).

### 13.2 Architecture & Changes
1. **`GitHubApiClient`**:
   - Added record `GitHubPagedResponse<T>(List<T> items, String nextUrl)`.
   - Added `getUserRepositoriesPage(String accessToken, int page, int perPage)` and `getUserRepositoriesPageByUrl(String accessToken, String url)`.
   - Implemented RFC 5988 `Link` header extraction in `extractNextUrl(String linkHeader)`: parses comma-separated link entries, identifies `rel="next"`, and extracts the canonical target URI.
   - Built on top of the resilient `executeWithRetry(...)` execution wrapper, providing automatic bounded exponential backoff on HTTP 403 / 429 (`Retry-After`) and transient HTTP 5xx errors.
2. **`GitHubIntegrationService`**:
   - Updated `synchronizeAllRepositories(User user)` to start at page 1 with `per_page=100`.
   - If GitHub returns a `Link` header with `rel="next"`, it follows `nextUrl` via `getUserRepositoriesPageByUrl(...)` until `nextUrl == null`.
   - If no `Link` header is provided (e.g. legacy or mocked environments), it cleanly falls back to incrementing `page++` as long as the page returned full items (`DEFAULT_SYNC_PAGE_SIZE = 100`).
   - Stop condition: stops safely when an empty page is returned, `nextUrl` is null, or the safety guard (`SAFETY_MAX_PAGES = 1000`, up to 100,000 repositories) is reached.
   - Safe deduplication: uses `seenGithubRepoIds.add(githubId)` before upserting each repository, preventing duplicate DB queries or duplicate records if GitHub returns repeated entries across pages.
   - Tenant isolation & non-destructive sync: all repositories are scoped strictly by `(githubRepoId, ownerId)`. Deactivation of inactive/deleted repositories only runs if `completedFullScan` is true. If an error or exception occurs at any point during pagination, `completedFullScan` is false, guaranteeing existing active repositories are never mistakenly marked inactive.

### 13.3 Test Verification & Coverage
All 8 test scenarios have been thoroughly verified:
1. **0 Repositories (Empty list)**: Returns empty list immediately, deactivates any existing repositories cleanly.
2. **< 100 Repositories (Single page)**: Ingests all repositories on page 1 without extraneous requests when no `next` link is present.
3. **Exactly 100 Repositories**: Follows link header or checks page 2; stops cleanly upon receiving empty page 2 without errors.
4. **> 100 Repositories (Multi-page, e.g. 250+ across 3 pages)**: Correctly paginates through all pages, ingesting all 250+ repositories into PostgreSQL.
5. **Deduplication across pages**: Handles overlapping repository IDs across pages without duplicate DB records.
6. **Renamed / Archived / Transferred Repositories**: Correctly updates `name`, `fullName`, and `isActive` based on immutable GitHub repository ID.
7. **Rate Limiting & Transient Network Failure**: Bounded retry with `Retry-After` backoff in `GitHubApiClient`; network failure throws `GitHubApiException` and preserves existing active records in PostgreSQL without marking them inactive.
8. **Tenant Isolation**: Asserts that records synced by User A are strictly bound to User A and cannot mutate or leak into User B's repositories.

---

## 14. Diff Parsing, Retry State Transitions & Failure Diagnostics Fix

### 14.1 Root Causes Identified from Live Docker Logs
1. **`Pull request diff has no reviewable source files` (`CodeReviewOrchestrator.java:61`)**:
   - `DiffParser.parseDiff` treated standard unified-diff `\ No newline at end of file` metadata as `current.setIncomplete(true)`, causing valid `.java` files without a trailing LF (such as `KunjMaheshwari/LeetCode` PR #1, #2, and #4) to be rejected by `isReviewable()`.
   - Additionally, `DiffParser.parseDiff` did not reset `sawHunk`, `oldLine`, and `newLine` at each `diff --git ` boundary, causing `index ...` headers in subsequent files of multi-file diffs to hit the fallback `incomplete = true` branch.
2. **`Invalid review job state transition` (`ReviewJobService.java:125`, `ReviewJobWorker.java:102`)**:
   - `CodeReviewOrchestrator` marked failed jobs as `FAILED` before returning to `ReviewJobWorker`, and `ReviewJobService.isAllowedTransition("FAILED", "RETRYING")` rejected the transition because `FAILED` was treated as unconditionally terminal.
   - Permanent failures (e.g., `NO_REVIEWABLE_FILES`, `EMPTY_DIFF`, non-retryable 4xx API errors) were not distinguished from transient failures, causing `ReviewJobWorker` to attempt `markRetrying(...)`, throw `IllegalStateException`, and leave payloads unacknowledged in Redis `codepilot:review-jobs:processing`.

### 14.2 Implemented Fixes
- **`DiffParser`**:
  - Ignores `\ No newline at end of file` metadata without setting `incomplete = true`.
  - Resets `sawHunk = false`, `oldLine = 0`, and `newLine = 0` on every `diff --git ` header.
  - Expanded non-reviewable asset/doc/lock exclusions (`.md`, `.txt`, `.gif`, `.svg`, `.ico`, `.webp`, `.pdf`, `.zip`, `.tar`, `.gz`, `.min.js`, `.min.css`, `.map`, `yarn.lock`, `pnpm-lock.yaml`, etc.).
- **`CodeReviewOrchestrator` & `ReviewJobService` & `ReviewJobWorker`**:
  - Classified exceptions into explicit categories: `EMPTY_DIFF`, `NO_REVIEWABLE_FILES`, `DIFF_TOO_LARGE`, `GITHUB_API_ERROR` / `GITHUB_API_TRANSIENT`, `GEMINI_API_ERROR` / `GEMINI_API_TRANSIENT`, `INVALID_AI_RESPONSE`, `RETRY_EXHAUSTED`, and `PROCESSING`.
  - Allowed idempotent `FAILED` / `PROCESSING` -> `RETRYING` transitions in `ReviewJobService.markRetrying` exclusively for recoverable failures, while blocking retries on permanent failures (`isPermanentFailure`).
  - `ReviewJobWorker` now acknowledges permanent failures without retrying or dead-lettering, schedules bounded exponential backoff + jitter in Redis ZSET for recoverable failures, and marks exhausted retries as `RETRY_EXHAUSTED` before moving payloads to the dead-letter queue and acknowledging.
- **Frontend Diagnostics (`ReviewJobStatusDto`, `api.ts`, `reviews/page.tsx`)**:
  - Exposed normalized `errorCategory` in `ReviewJobStatusDto` and rendered category-specific failure banners (`Empty pull request diff`, `No supported source files`, `GitHub API failure`, `Gemini API failure`, `Invalid AI response`, `Retry exhaustion`).

---

## 15. Gemini API Model Configuration & Diagnostics Fix

### 15.1 Investigation Findings
- **Endpoint & Format**: `GeminiCodeReviewProvider` calls `POST {GEMINI_API_URL}/v1beta/models/{model}:generateContent` with `x-goog-api-key` header, `systemInstruction`, `contents`, and `generationConfig` (`responseMimeType: "application/json"`, `temperature: 0.1`, `responseSchema`), matching the official Gemini `v1beta` REST specification.
- **Model Availability**: Querying `GET /v1beta/models` against the configured project confirmed `models/gemini-3.8-flash` is available and supports `generateContent`, `countTokens`, `createCachedContent`, and `batchGenerateContent`.
- **Configuration & Diagnostics Gaps**:
  - `application.yml`, `docker-compose.yml`, and `.env.example` used `GEMINI_API_MODEL` defaulting to `gemini-2.5-flash` instead of `GEMINI_MODEL`.
  - `GeminiCodeReviewProvider` did not normalize trailing slashes on `apiUrl` or `models/` prefixes on `model`, and HTTP error logs/exceptions omitted the resolved model name and Google's safe `error.message` / `error.status`.

### 15.2 Implemented Fixes
- **`application.yml` / `docker-compose.yml` / `.env.example` / `.env`**:
  - Switched default model to `gemini-3.8-flash` configurable via `GEMINI_MODEL` (with fallback to `GEMINI_API_MODEL` for backward compatibility).
- **`GeminiCodeReviewProvider.java`**:
  - Normalized `apiUrl` (trailing `/`) and `model` (`models/` prefix).
  - Extracted Google's safe `error.status` and `error.message` from JSON error payloads (redacting any `apiKey` or `?key=` query values) and included the resolved model identifier in logs and `IllegalStateException` messages without logging keys or prompt contents.
- **`GeminiCodeReviewProviderTest.java`**:
  - Added tests for `gemini-3.8-flash` generation with URL/prefix normalization, 404 model-not-found safe diagnostics, 403 authentication errors, 429 quota retries/exhaustion, and malformed JSON responses.

### 15.3 Lightweight Gemini Connectivity Smoke-Test Procedure
To verify Gemini API connectivity, authentication, and model availability without triggering a code review or consuming generation tokens, run:
```bash
set -a && . ./.env && set +a
curl -fsS -H "x-goog-api-key: $GEMINI_API_KEY" \
  "${GEMINI_API_URL}/v1beta/models/${GEMINI_MODEL}"
```
- **Expected result**: HTTP `200` JSON metadata showing `"name": "models/gemini-3.8-flash"` and `"generateContent"` in `supportedGenerationMethods`.
- **Verified**: Executed against live `https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash` (HTTP `200`, `supportedGenerationMethods` includes `generateContent`), plus a minimal 4-token `generateContent` check (`HTTP 200`, `modelVersion: "gemini-3.8-flash"`).

### 10. Performance & Disaster Recovery Assessment
- HikariCP (`max=10`), JVM `-XX:MaxRAMPercentage=75.0`, diff size caps (`100 KB` file / `2 MB` total), and paginated repositories/PRs/reviews bound per-request resource usage.
- **Disaster Recovery & Operations**:
  - **PostgreSQL Backup/Restore**: Schedule automated `pg_dump` / managed snapshots on `postgres_data`; test point-in-time or dump restore into a staging DB before running new Flyway migrations (migrations are forward-only; rollback requires restoring a pre-migration snapshot or authoring a compensating forward migration).
  - **Redis Persistence & Crash Recovery**: AOF (`--appendonly yes`) preserves queued jobs across restarts; PostgreSQL remains the source of truth, and stale `PROCESSING` / `QUEUED` jobs are recovered by `ReviewJobRecoveryScheduler`.
  - **Logging, Metrics & Alerting**: Collect container stdout JSON/structured logs centrally; scrape `/actuator/metrics` (authenticated) and alert on readiness probe failures, Hikari pool exhaustion, DLQ growth (`codepilot:review-jobs:dead-letter`), and elevated `5xx` rates.
  - **Secret Rotation**: Rotate `GITHUB_CLIENT_SECRET`, `GITHUB_WEBHOOK_SECRET`, and `GEMINI_API_KEY` via environment secret updates + rolling restart. Rotating `ENCRYPTION_KEY` requires a key-versioned re-encryption migration for stored `connected_github_accounts.access_token`.

### 11. Remaining P0 / P1 / P2 / P3 Issues
- **P0 (Critical)**: None remaining.
- **P1 (High)**:
  - Live external integration verification (GitHub OAuth App callback, live GitHub webhook delivery, and live Gemini API key) must be validated in staging before opening to external users.
- **P2 (Medium)**:
  - `RateLimitFilter` uses an in-memory `ConcurrentHashMap` and reads `X-Forwarded-For` directly; replace with Redis-backed distributed rate limiting and trusted-proxy IP resolution before scaling backend replicas > 1.
  - Unauthenticated `/api/v1/**` requests currently return `302` redirect to `/oauth2/authorization/github` rather than `401 Unauthorized` (handled in `frontend/src/lib/api.ts`, but returning `401` via `AuthenticationEntryPoint` for `/api/**` is cleaner).
  - `application.yml` leaves `spring.jpa.open-in-view` at Spring Boot's default (`true`), emitting a startup warning; disabling it (`open-in-view: false`) after verifying lazy-loading boundaries will eliminate the warning.
- **P3 (Low)**:
  - Pin third-party GitHub Actions in `ci.yml` / `deploy.yml` to immutable commit SHAs instead of major version tags (`@v4`, `@v6`, `@0.28.0`) for stricter supply-chain hardening.
  - Add a concrete deployment target implementation to `.github/workflows/deploy.yml` and pass environment-specific `NEXT_PUBLIC_API_BASE_URL` per staging/production image build.

### 12. Deployment Prerequisites & Launch Checklist
1. Provision managed PostgreSQL 17 and Redis 7 (or persistent container volumes with automated backups) and set strong credentials.
2. Generate a unique 256-bit `ENCRYPTION_KEY` (`openssl rand -hex 32`) and configure `GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET`, `GITHUB_WEBHOOK_SECRET`, and `GEMINI_API_KEY` in the target secret store.
3. Configure TLS termination at the reverse proxy/load balancer forwarding `X-Forwarded-Proto: https` and `X-Forwarded-Host`.
4. Set `COOKIE_SECURE=true`, `FRONTEND_URL=https://<frontend-domain>`, `CORS_ALLOWED_ORIGINS=https://<frontend-domain>`, `CODEPILOT_SWAGGER_PUBLIC=false`, and `ACTUATOR_HEALTH_SHOW_DETAILS=never`.
5. Build the frontend image with `NEXT_PUBLIC_API_BASE_URL=https://<backend-domain>`.
6. Register the GitHub OAuth callback URL (`https://<backend-domain>/login/oauth2/code/github`) and webhook URL (`https://<backend-domain>/api/v1/webhooks/github`).
7. Keep `CODEPILOT_REVIEW_PUBLISH_COMMENTS_ENABLED=false` during initial smoke testing; enable only after verifying review quality in staging.
