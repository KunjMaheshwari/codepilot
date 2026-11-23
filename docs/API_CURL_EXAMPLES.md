# Authenticated API `curl` Examples

CodePilot uses session cookies (`JSESSIONID`) with Spring Security's `CookieCsrfTokenRepository` (`XSRF-TOKEN` cookie + `X-XSRF-TOKEN` header).

## 1. Fetch a CSRF Token

```bash
curl -s -c cookies.txt http://localhost:8080/api/v1/auth/csrf | jq .
XSRF_TOKEN=$(awk '$6 == "XSRF-TOKEN" {print $7}' cookies.txt)
```

## 2. Sign In via Browser OAuth

Open `http://localhost:8080/oauth2/authorization/github` in a browser, then export your `JSESSIONID` and `XSRF-TOKEN` cookies into `cookies.txt` (or copy them from DevTools) for CLI testing.

## 3. List Connected Repositories & Pull Requests

```bash
curl -s -b cookies.txt "http://localhost:8080/api/v1/repositories?page=0&size=20" | jq .
curl -s -b cookies.txt "http://localhost:8080/api/v1/pull-requests?page=0&size=20" | jq .
```

## 4. Trigger a Pull Request Review (`POST` with CSRF)

```bash
curl -s -b cookies.txt -c cookies.txt \
  -X POST \
  -H "Content-Type: application/json" \
  -H "X-XSRF-TOKEN: ${XSRF_TOKEN}" \
  -d '{"pullRequestId":"<PULL_REQUEST_UUID>"}' \
  "http://localhost:8080/api/v1/repositories/<REPOSITORY_UUID>/reviews" | jq .
```

## 5. Poll Review Job Status & Fetch Findings

```bash
curl -s -b cookies.txt "http://localhost:8080/api/v1/reviews/<REVIEW_UUID>/job" | jq .
curl -s -b cookies.txt "http://localhost:8080/api/v1/reviews/<REVIEW_UUID>/findings?page=0&size=50" | jq .
```
