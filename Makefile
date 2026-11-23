.PHONY: help backend-test backend-verify frontend-lint frontend-typecheck frontend-build frontend-e2e test compose-config compose-up compose-down

help:
	@echo "CodePilot Task Runner"
	@echo "  make backend-test       Run backend unit/mocked tests"
	@echo "  make backend-verify     Run backend verify lifecycle"
	@echo "  make frontend-lint      Run frontend ESLint"
	@echo "  make frontend-typecheck Run frontend TypeScript check"
	@echo "  make frontend-build     Build frontend production bundle"
	@echo "  make frontend-e2e       Run Playwright E2E suite"
	@echo "  make test               Run backend tests, frontend lint, typecheck, and build"
	@echo "  make compose-config     Validate docker-compose.yml"
	@echo "  make compose-up         Start Docker Compose stack"
	@echo "  make compose-down       Stop Docker Compose stack"

backend-test:
	cd backend && mvn clean test -B

backend-verify:
	cd backend && mvn verify -B

frontend-lint:
	cd frontend && npm run lint

frontend-typecheck:
	cd frontend && npx tsc --noEmit

frontend-build:
	cd frontend && npm run build

frontend-e2e:
	cd frontend && npx playwright test

test: backend-test frontend-lint frontend-typecheck frontend-build

compose-config:
	docker compose --env-file .env.example config -q

compose-up:
	docker compose up -d --build

compose-down:
	docker compose down
