# CodePilot Docker Setup Guide

## Architecture Overview

CodePilot runs as four isolated containers on the `codepilot-network` bridge network:

1. **`postgres` (`postgres:17-alpine`)**
   - Persistent volume: `postgres_data` mounted at `/var/lib/postgresql/data`.
   - Internal port `5432` only (not exposed on the host by default).
   - Health check: `pg_isready -U ${POSTGRES_USER} -d ${POSTGRES_DB}`.
2. **`redis` (`redis:7-alpine`)**
   - Persistent volume: `redis_data` mounted at `/data` with AOF persistence (`--appendonly yes`).
   - Internal port `6379` only (not exposed on the host by default).
   - Health check: `redis-cli ping` (authenticated if `REDIS_PASSWORD` is set).
3. **`backend` (`backend/Dockerfile`)**
   - Multi-stage build: `maven:3.9.9-eclipse-temurin-21-alpine` builder -> `eclipse-temurin:21-jre-alpine` runtime.
   - Runs as non-root user `codepilot`.
   - JVM container memory flags: `-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC`.
   - Graceful shutdown enabled (`server.shutdown: graceful`, 30s timeout).
   - Waits for `postgres` and `redis` to be healthy (`condition: service_healthy`).
   - Runs Flyway migrations automatically on startup and validates JPA schema (`hibernate.ddl-auto: validate`).
   - Health check: `GET /actuator/health/readiness`.
4. **`frontend` (`frontend/Dockerfile`)**
   - Multi-stage build: `node:22-alpine` deps (`npm ci`) -> builder (`npm run build` with `output: "standalone"`) -> minimal `node:22-alpine` runner.
   - Runs as non-root user `nextjs`.
   - Waits for `backend` to be healthy (`condition: service_healthy`).
   - Health check: `GET /api/health`.

## Avoiding Local Port Conflicts

If you already run EnterpriseDB PostgreSQL 17 on port `5432` or Homebrew Redis on port `6379`:
- `docker-compose.yml` does **not** bind host ports for `postgres` or `redis`. They only communicate internally over `codepilot-network`, so your local PostgreSQL and Redis services are untouched.
- If ports `3000` or `8080` are already in use on your host, override `FRONTEND_HOST_PORT` and `BACKEND_HOST_PORT` in `.env` (and update `NEXT_PUBLIC_API_BASE_URL`, `FRONTEND_URL`, and `CORS_ALLOWED_ORIGINS` accordingly).

## Build and Run Commands

```bash
# 1. Create your local environment file
cp .env.example .env

# 2. Validate Compose configuration
docker compose config -q

# 3. Build backend and frontend images
docker compose build

# 4. Start the full stack in detached mode
docker compose up -d

# 5. Watch startup logs and health status
docker compose ps
docker compose logs -f backend frontend
```

## Clean Teardown vs. Persistent Restart

```bash
# Stop containers while preserving postgres_data and redis_data volumes
docker compose down

# Restart without losing database or Redis data
docker compose up -d

# Disposable teardown (destroys container volumes only; never touches local host DBs)
docker compose down -v
```

