# CodePilot Environment Variables Reference

Copy `.env.example` to `.env` for Docker Compose, or `backend/.env.example` / `frontend/.env.example` for standalone local runs. Never commit `.env` files or real secrets.

## Host & Compose Ports

| Variable | Required | Default | Description |
| --- | --- | --- | --- |
| `FRONTEND_HOST_PORT` | No | `3000` | Host port mapped to the Next.js container (`3000`). |
| `BACKEND_HOST_PORT` | No | `8080` | Host port mapped to the Spring Boot container (`8080`). |

## PostgreSQL

| Variable | Required | Default | Description |
| --- | --- | --- | --- |
| `POSTGRES_DB` | No | `codepilot` | Database name created in the `postgres` container. |
| `POSTGRES_USER` | No | `codepilot` | Database user for PostgreSQL and Flyway. |
| `POSTGRES_PASSWORD` | **Yes** | — | Database password. Compose fails fast if unset. |
| `DB_URL` | **Yes** (standalone) | Compose sets `jdbc:postgresql://postgres:5432/${POSTGRES_DB}` | JDBC connection URL. |
| `DB_USERNAME` | **Yes** (standalone) | — | JDBC username. |
| `DB_PASSWORD` | **Yes** (standalone) | — | JDBC password. |
| `DB_POOL_MAX_SIZE` | No | `10` | HikariCP maximum pool size. |
| `DB_POOL_MIN_IDLE` | No | `2` | HikariCP minimum idle connections. |

## Redis

| Variable | Required | Default | Description |
| --- | --- | --- | --- |
| `REDIS_HOST` | No | `localhost` (Compose: `redis`) | Redis hostname. |
| `REDIS_PORT` | No | `6379` | Redis port. |
| `REDIS_USERNAME` | No | `""` | Optional Redis ACL username. |
| `REDIS_PASSWORD` | No | `""` | Optional Redis password (enabled via `--requirepass` in Compose if non-empty). |

## GitHub OAuth & Webhooks

| Variable | Required | Default | Description |
| --- | --- | --- | --- |
| `GITHUB_CLIENT_ID` | **Yes** | — | GitHub OAuth App Client ID. |
| `GITHUB_CLIENT_SECRET` | **Yes** | — | GitHub OAuth App Client Secret. |
| `GITHUB_WEBHOOK_SECRET` | Recommended | `""` | HMAC-SHA256 secret for `/api/v1/webhooks/github` signature verification. |

## Google Gemini AI

| Variable | Required | Default | Description |
| --- | --- | --- | --- |
| `GEMINI_API_KEY` | **Yes** | — | Google Gemini API key. |
| `GEMINI_API_URL` | No | `https://generativelanguage.googleapis.com` | Gemini API base URL. |
| `GEMINI_API_MODEL` | No | `gemini-2.5-flash` | Gemini model identifier. |

## Security, Cookies, CORS & Frontend

| Variable | Required | Default | Description |
| --- | --- | --- | --- |
| `ENCRYPTION_KEY` | **Yes** | — | 64-char hex key (32 bytes / AES-256-GCM) for encrypting OAuth tokens at rest. Generate with `openssl rand -hex 32`. |
| `FRONTEND_URL` | No | `http://localhost:3000` | OAuth2 login redirect base URL. |
| `CORS_ALLOWED_ORIGINS` | No | `http://localhost:3000` | Comma-separated trusted browser origins (wildcards rejected). |
| `NEXT_PUBLIC_API_BASE_URL` | No | `http://localhost:8080` | Public backend URL baked into the Next.js client bundle at build time. Never store secrets in `NEXT_PUBLIC_*`. |
| `COOKIE_SECURE` | Production: `true` | `false` | Sets `Secure` attribute on `JSESSIONID` and `XSRF-TOKEN` cookies. Must be `true` over HTTPS. |
| `COOKIE_SAME_SITE` | No | `Lax` | `SameSite` policy for session and CSRF cookies (`Lax` or `None` when cross-site over HTTPS). |
| `SESSION_TIMEOUT` | No | `30m` | HTTP session idle timeout. |
| `CODEPILOT_SWAGGER_PUBLIC` | No | `false` | Whether `/swagger-ui.html` and `/v3/api-docs` are accessible without login. Keep `false` in production. |
| `ACTUATOR_HEALTH_SHOW_DETAILS` | No | `never` | Actuator health detail visibility (`never`, `when-authorized`, `always`). Keep `never` in production. |

## Review Engine & Publishing

| Variable | Required | Default | Description |
| --- | --- | --- | --- |
| `CODEPILOT_REVIEW_PUBLISH_COMMENTS_ENABLED` | No | `false` | Feature flag controlling whether AI findings are posted to GitHub PRs. |
| `CODEPILOT_REVIEW_MAX_FILE_BYTES` | No | `102400` | Maximum per-file diff size (bytes) sent to Gemini. |
| `CODEPILOT_REVIEW_MAX_DIFF_BYTES` | No | `2097152` | Maximum total PR diff size (bytes) processed per review. |
| `CODEPILOT_REVIEW_MAX_ATTEMPTS` | No | `3` | Maximum async review job retry attempts before DLQ. |
| `CODEPILOT_REVIEW_ASYNC_ENABLED` | No | `true` | Enables Redis-backed background `ReviewJobWorker` polling. |

