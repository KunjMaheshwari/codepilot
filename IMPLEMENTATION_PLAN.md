# CodePilot Implementation Plan

## 1. Repository Assessment

The repository is currently empty, meaning we are starting from scratch. We will initialize two main directories: `/frontend` and `/backend` to keep the codebase organized within a monorepo structure.

## 2. Recommended Folder Structure

```
/
├── frontend/                  # Next.js frontend application
│   ├── src/
│   │   ├── app/               # App Router pages and layouts
│   │   ├── components/        # Reusable UI components (shadcn/ui)
│   │   ├── lib/               # Utility functions and API clients
│   │   ├── hooks/             # Custom React hooks
│   │   ├── types/             # TypeScript type definitions
│   │   └── styles/            # Tailwind CSS configuration
│   ├── package.json
│   └── tailwind.config.js
├── backend/                   # Spring Boot application
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/codepilot/
│   │   │   │   ├── config/    # Spring, Security, Redis, GitHub configurations
│   │   │   │   ├── controller/# REST API endpoints
│   │   │   │   ├── service/   # Business logic (AI pipeline, GitHub, etc.)
│   │   │   │   ├── repository/# Spring Data JPA interfaces
│   │   │   │   ├── model/     # JPA entities
│   │   │   │   ├── dto/       # Data Transfer Objects
│   │   │   │   └── security/  # Security filters, OAuth handlers
│   │   │   └── resources/
│   │   │       ├── application.yml # Environment configurations
│   │   │       └── db/migration/   # Flyway or Liquibase migrations
│   │   └── test/              # JUnit & Mockito tests
│   ├── pom.xml
│   └── Dockerfile
├── e2e-tests/                 # Playwright E2E tests
├── docker-compose.yml         # Local dev environment (PostgreSQL, Redis)
└── README.md
```

## 3. Backend Modules and Responsibilities

- `auth`: Handles GitHub OAuth login, JWT generation, and session management.
- `github`: Integrates with GitHub REST API and Webhooks to fetch PR details, changed files, and post comments.
- `ai`: Manages the integration with the Gemini API to analyze code diffs and generate review comments.
- `review`: Orchestrates the review process (webhook reception -> fetch PR -> analyze -> post comments).
- `user`: Manages user accounts, repository settings, and billing/limits (if applicable).
- `worker`: Handles asynchronous job processing via Redis for long-running AI review tasks.

## 4. Frontend Pages and Components

- **Pages**:
  - `/`: Landing page emphasizing the AI Code Review features.
  - `/login`: GitHub OAuth login page.
  - `/dashboard`: User dashboard displaying connected repositories and recent reviews.
  - `/repos/[id]`: Repository details, settings, and webhook management.
  - `/reviews/[id]`: Detailed view of a specific PR review.
- **Components**:
  - Layouts: Authenticated layout with sidebar navigation.
  - UI: shadcn/ui components (Buttons, Cards, Modals, Forms, Data Tables).
  - Feedback: Loading spinners, error messages, and success toasts using Framer Motion for smooth transitions.
  - Icons: Lucide React icons.

## 5. Database Entities and Relationships

- `User`: `id`, `github_id`, `username`, `email`, `created_at`, `updated_at`.
- `Repository`: `id`, `github_repo_id`, `name`, `full_name`, `owner_id` (FK to User), `webhook_id`, `is_active`.
- `ReviewRequest`: `id`, `repository_id` (FK to Repository), `pr_number`, `status` (PENDING, PROCESSING, COMPLETED, FAILED), `created_at`, `completed_at`.
- `ReviewComment`: `id`, `review_request_id` (FK to ReviewRequest), `file_path`, `line_number`, `comment`, `created_at`.

## 6. API Endpoints

- `GET /api/v1/auth/github`: Initiate GitHub OAuth.
- `GET /api/v1/users/me`: Get current authenticated user.
- `GET /api/v1/repos`: List repositories for the user.
- `POST /api/v1/repos/{id}/enable`: Enable CodePilot for a repo (installs webhook).
- `GET /api/v1/repos/{id}/reviews`: List recent reviews for a repo.
- `POST /api/v1/webhooks/github`: Webhook receiver for GitHub PR events.

## 7. GitHub Integration Architecture

- **OAuth App**: Setup for user authentication.
- **REST API**: Usage with user-specific tokens to list repositories and create webhooks.
- **Webhooks**: Configured to listen to `pull_request` events (opened, synchronize).
- **Security**: Backend verifies webhook signatures to ensure authenticity using HMAC.
- **Action**: Upon PR event, backend fetches PR diff/files via GitHub REST API, processes them, and posts review comments to the PR timeline.

## 8. AI Review Pipeline

- **Extract diffs**: Fetch changed files from the PR.
- **Pre-processing**: Filter out large files, minified files, or binary files to stay within Gemini API token limits.
- **Prompt construction**: Combine diff with repository context and a system prompt defining the Staff Engineer code review persona.
- **Gemini API Call**: Send prompt to Gemini API asynchronously.
- **Post-processing**: Parse the AI response into structured comments mapped to specific files and line numbers.

## 9. Background Job Processing

- **Problem**: AI review tasks can be slow, exceeding the strict 10-second GitHub webhook timeout limits.
- **Solution**: The backend acknowledges the webhook immediately and places a job payload onto a Redis queue. Worker threads process the queue (fetch PR diff, call Gemini API, post comments to GitHub, and update `ReviewRequest` status).

## 10. Security Considerations

- Securely store OAuth tokens (encrypted at rest).
- Validate all GitHub webhook payloads via HMAC signatures.
- Implement rate limiting to prevent abuse.
- Secure frontend-backend communication using HTTP-only cookies or JWTs.
- Sanitize Gemini API outputs to prevent XSS if displayed on the frontend.

## 11. Testing Strategy

- **Backend**: JUnit 5 and Mockito for unit testing services. Spring Boot Test for integration tests (with Testcontainers for PostgreSQL and Redis).
- **Frontend**: Jest/React Testing Library for component tests.
- **E2E**: Playwright to test the user flow from login to dashboard and repository setup.

## 12. Development Phases and Acceptance Criteria

- **Phase 1**: Setup & Infrastructure (Repos, CI/CD, DB, Redis, Next.js, Spring Boot skeletons).
  - *Acceptance:* Both apps start locally, DB/Redis run via Docker Compose.
- **Phase 2**: Auth & GitHub Integration (OAuth, listing repos, creating webhooks).
  - *Acceptance:* User can log in with GitHub and see their repositories. Webhook can be created.
- **Phase 3**: AI Pipeline & Background Processing (Fetching diffs, Redis queue, Gemini API integration).
  - *Acceptance:* Webhook triggers a job. Job successfully posts a hardcoded or basic AI comment on a test PR.
- **Phase 4**: Frontend Development (Dashboard, repo settings, review history).
  - *Acceptance:* Full UI parity with the features built in Phases 2 and 3.
- **Phase 5**: Testing, Polish, and Production Readiness.
  - *Acceptance:* E2E tests pass, edge cases handled (e.g. rate limits), ready for deployment.

## Architectural Risks & Solutions

1. **Token Limits / Cost for Gemini API**: Risk of large PRs exceeding context windows.
   - *Solution*: Filter non-essential files (e.g. `package-lock.json`), split large diffs into chunks, and enforce PR size limits.
2. **Webhook Timeout**: GitHub webhooks timeout if not acknowledged quickly.
   - *Solution*: Acknowledge webhooks immediately and process the AI pipeline asynchronously using Redis.
3. **Rate Limits (GitHub API)**: Exhausting API limits during processing.
   - *Solution*: Cache aggressively and implement backoff/retry strategies for API calls. Use GitHub App installation tokens which have higher rate limits if possible.
