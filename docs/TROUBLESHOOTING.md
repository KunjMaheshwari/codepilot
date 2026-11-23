# Local Troubleshooting Guide

## Startup Fails on `ENCRYPTION_KEY`
- **Symptom**: `StartupConfigurationValidator` rejects startup at boot.
- **Fix**: `ENCRYPTION_KEY` must be a 64-character hex string (32 bytes for AES-256-GCM). Generate one with:
  ```bash
  openssl rand -hex 32
  ```

## PostgreSQL / Flyway Migration Errors
- **Symptom**: `Validate` or Flyway checksum/schema mismatch on startup.
- **Fix**: Ensure PostgreSQL 17 is running and `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` match `.env`. Never use `hibernate.ddl-auto=update`; all schema changes live in `backend/src/main/resources/db/migration/`.

## Redis Connection Refused
- **Symptom**: `RedisConnectionFailureException` in backend logs.
- **Fix**: Confirm Redis is running (`docker compose ps`) and `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` match your environment.

## GitHub OAuth Redirect Mismatch
- **Symptom**: GitHub returns `redirect_uri_mismatch` during login.
- **Fix**: Register `http://localhost:8080/login/oauth2/code/github` (or your proxied frontend URL when using Next.js rewrites) in your GitHub OAuth App settings.

## Gemini API 404 or 429
- **Symptom**: Review job fails with `GEMINI_API_ERROR` (404) or `GEMINI_API_TRANSIENT` (429).
- **Fix**: Verify `GEMINI_API_KEY` and `GEMINI_API_MODEL` (default `gemini-3-flash-preview`) in `.env`. Transient 429/5xx errors are retried automatically by `ReviewJobWorker`.

## Testcontainers Skipped Locally
- **Symptom**: `mvn verify` skips `AsyncReliabilityIntegrationTest` / `PostgresRedisInfrastructureIntegrationTest`.
- **Fix**: Start Docker Desktop before running `mvn verify -B`.
