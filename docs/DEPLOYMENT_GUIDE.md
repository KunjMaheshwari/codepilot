# CodePilot Deployment & CI/CD Guide

## Pre-Deployment Checklist

1. **Secrets & Keys**
   - Generate a unique `ENCRYPTION_KEY`: `openssl rand -hex 32`.
   - Configure production `POSTGRES_PASSWORD`, `REDIS_PASSWORD`, `GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET`, `GITHUB_WEBHOOK_SECRET`, and `GEMINI_API_KEY` in your secret manager / GitHub Environment.
2. **HTTPS, Cookies & CORS**
   - Terminate TLS at your reverse proxy or load balancer.
   - Set `COOKIE_SECURE=true`.
   - Set `FRONTEND_URL` and `CORS_ALLOWED_ORIGINS` to your exact HTTPS frontend origin (e.g., `https://app.codepilot.example`).
   - Rebuild the frontend image with `NEXT_PUBLIC_API_BASE_URL` pointing to your public HTTPS backend origin (e.g., `https://api.codepilot.example`).
   - Keep `CODEPILOT_SWAGGER_PUBLIC=false` and `ACTUATOR_HEALTH_SHOW_DETAILS=never`.
3. **Database & Redis**
   - Flyway migrations (`classpath:db/migration`, `V1`–`V7`) execute automatically on backend startup; Hibernate runs with `ddl-auto: validate`.
   - Never use `ddl-auto=create` or `create-drop`.
   - Back up PostgreSQL before applying new migrations in production.

## Health Checks & Observability

- **Backend Liveness**: `GET /actuator/health/liveness` (unauthenticated, returns `{"status":"UP"}`).
- **Backend Readiness**: `GET /actuator/health/readiness` (unauthenticated, verifies database & Redis readiness without leaking internal details when `ACTUATOR_HEALTH_SHOW_DETAILS=never`).
- **Backend Metrics**: `GET /actuator/metrics` and `GET /actuator/info` (authenticated).
- **Frontend Health**: `GET /api/health` (returns `{"status":"ok"}` with HTTP `200`).

## CI/CD Workflows (`.github/workflows/`)

### 1. Pull Request & Main CI (`.github/workflows/ci.yml`)
Runs on pull requests to `main`/`develop` and pushes to `main` with `contents: read` least-privilege permissions:
- **`backend-test`**: Java 21 (`temurin`), Maven cache, `mvn clean test -B`, `mvn verify -B` (including Testcontainers PostgreSQL 17 & Redis 7 integration tests).
- **`frontend-test`**: Node 22, npm cache, `npm ci`, `npm run lint`, `npx tsc --noEmit`, `npm run build`, `npx playwright install --with-deps chromium`, `npx playwright test`.
- **`docker-validation`**: Validates `docker compose --env-file .env.example config -q`, builds backend & frontend images (`push: false`), and runs advisory Trivy scans (`CRITICAL,HIGH`, `ignore-unfixed: true`).

### 2. Release & Deployment Preparation (`.github/workflows/deploy.yml`)
- Triggers **only** on `release: [published]` or manual `workflow_dispatch` (`staging` or `production`).
- Uses GitHub `environment` protection rules so manual approval gates can be enforced before pushing or deploying.
- Validates required environment secrets (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET`, `GEMINI_API_KEY`, `ENCRYPTION_KEY`) before running the configurable deployment hook.

