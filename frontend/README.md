# CodePilot frontend

The Next.js App Router frontend uses the Spring Boot API with the browser's
credentialed session cookie. Configure the public API origin in `.env.local`:

```bash
NEXT_PUBLIC_API_BASE_URL=http://localhost:8080
```

Only the API origin belongs in the frontend environment. GitHub, Gemini,
database, Redis, and encryption secrets must remain backend-only.

Start the backend with PostgreSQL, Redis, GitHub OAuth credentials, and its
required environment variables, then run:

```bash
npm install
npm run dev
```

Open `http://localhost:3000` and sign in with GitHub. OAuth and access-token
storage are owned by the backend; the frontend sends `credentials: include`
for API requests. The dashboard, repositories, pull requests, reviews, job
polling, findings, and GitHub disconnect action use the existing backend
endpoints.

## API mapping

| Frontend feature | Backend endpoint | Status |
| --- | --- | --- |
| GitHub authentication | `GET /oauth2/authorization/github` | Integrated |
| Dashboard statistics | `GET /api/v1/dashboard` | Integrated |
| Repository listing | `GET /api/v1/repositories` | Integrated |
| Repository synchronization | `POST /api/v1/repositories/sync` | Integrated |
| Pull requests | `GET /api/v1/repositories/{id}/pull-requests` | Integrated |
| Review creation | `POST /api/v1/repositories/{id}/reviews` | Integrated |
| Review status | `GET /api/v1/reviews/{id}/job` | Integrated with polling |
| Review findings | `GET /api/v1/reviews/{id}/findings` | Integrated |
| GitHub disconnection | `DELETE /api/v1/auth/github` | Integrated |

The backend currently exposes aggregate dashboard counts rather than activity
history or severity buckets. The UI displays only the values supplied by that
contract and does not invent missing metrics.
