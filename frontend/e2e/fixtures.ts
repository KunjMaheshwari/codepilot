/* eslint-disable react-hooks/rules-of-hooks */
import { expect, test as base, type Page } from "@playwright/test";

export const mockUser = {
  id: "11111111-1111-1111-1111-111111111111",
  username: "octocat",
  email: "octocat@example.com",
  createdAt: "2026-11-01T10:00:00Z",
};

export const mockRepos = [
  {
    id: "22222222-2222-2222-2222-222222222221",
    name: "codepilot-api",
    fullName: "octocat/codepilot-api",
    description: "Core backend service",
    githubRepoId: "1001",
    isActive: true,
    isPrivate: true,
    defaultBranch: "main",
    htmlUrl: "https://github.com/octocat/codepilot-api",
    lastSyncedAt: "2026-11-12T10:00:00Z",
  },
  {
    id: "22222222-2222-2222-2222-222222222222",
    name: "legacy-cli",
    fullName: "octocat/legacy-cli",
    description: "Archived CLI repository",
    githubRepoId: "1002",
    isActive: false,
    isPrivate: false,
    defaultBranch: "master",
    htmlUrl: "https://github.com/octocat/legacy-cli",
    lastSyncedAt: "2026-11-10T10:00:00Z",
  },
];

export const mockPullRequests = [
  {
    id: "33333333-3333-3333-3333-333333333331",
    repositoryId: mockRepos[0].id,
    prNumber: 42,
    title: "Harden OAuth session rotation",
    author: "octocat",
    status: "open",
    baseBranch: "main",
    headBranch: "feat/session-rotation",
    headSha: "abc1234def5678",
  },
  {
    id: "33333333-3333-3333-3333-333333333332",
    repositoryId: mockRepos[0].id,
    prNumber: 41,
    title: "Add Redis retry queue",
    author: "octocat",
    status: "merged",
    baseBranch: "main",
    headBranch: "feat/redis-retry",
    headSha: "deadbeef1234",
  },
  {
    id: "33333333-3333-3333-3333-333333333333",
    repositoryId: mockRepos[0].id,
    prNumber: 40,
    title: "Experiment reverted",
    author: "octocat",
    status: "closed",
    baseBranch: "main",
    headBranch: "chore/exp",
    headSha: "cafebabe9999",
  },
];

export const mockFindings = [
  {
    id: "55555555-5555-5555-5555-555555555551",
    filePath: "src/auth/SessionFilter.java",
    lineNumber: 42,
    severity: "CRITICAL",
    title: "Session fixation risk on OAuth callback",
    description: "Session ID is not rotated after authentication completes.",
    category: "SECURITY",
    evidence: "session.setAttribute(\"USER\", user);",
    impact: "Pre-authentication session cookie remains valid.",
    suggestedFix: "request.changeSessionId();",
    confidence: "HIGH",
    message: "Session ID is not rotated after authentication completes.",
  },
  {
    id: "55555555-5555-5555-5555-555555555552",
    filePath: "src/auth/TokenStore.java",
    lineNumber: 18,
    severity: "MEDIUM",
    title: "Unbounded in-memory map growth",
    description: "Entries are never evicted from the cache map.",
    category: "RELIABILITY",
    evidence: "private final Map<String, String> cache = new ConcurrentHashMap<>();",
    impact: "Memory usage grows indefinitely under load.",
    suggestedFix: "Use a bounded cache with TTL eviction.",
    confidence: "MEDIUM",
    message: "Entries are never evicted from the cache map.",
  },
];

export const mockReviews = [
  {
    id: "44444444-4444-4444-4444-444444444441",
    pullRequestId: mockPullRequests[0].id,
    jobId: "66666666-6666-6666-6666-666666666661",
    status: "COMPLETED",
    createdAt: "2026-11-12T10:05:00Z",
    completedAt: "2026-11-12T10:05:30Z",
  },
  {
    id: "44444444-4444-4444-4444-444444444442",
    pullRequestId: mockPullRequests[0].id,
    jobId: "66666666-6666-6666-6666-666666666662",
    status: "PARTIAL",
    createdAt: "2026-11-12T09:00:00Z",
    completedAt: "2026-11-12T09:00:25Z",
  },
  {
    id: "44444444-4444-4444-4444-444444444443",
    pullRequestId: mockPullRequests[0].id,
    jobId: "66666666-6666-6666-6666-666666666663",
    status: "FAILED",
    createdAt: "2026-11-12T08:00:00Z",
    completedAt: "2026-11-12T08:00:15Z",
  },
];

export function pageOf<T>(content: T[]) {
  return {
    content,
    totalElements: content.length,
    totalPages: content.length === 0 ? 0 : 1,
    number: 0,
    size: 20,
    last: true,
    first: true,
  };
}

export async function setupAuthenticatedApi(page: Page, options?: { empty?: boolean }) {
  const empty = options?.empty ?? false;
  let signedIn = true;

  await page.route("**/api/v1/**", async (route) => {
    const url = new URL(route.request().url());
    const path = url.pathname;
    const method = route.request().method();

    if (path === "/api/v1/auth/csrf") {
      return route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ token: "test-xsrf-token" }),
      });
    }

    if (!signedIn && path !== "/api/v1/auth/logout") {
      return route.fulfill({
        status: 401,
        contentType: "application/json",
        body: JSON.stringify({ message: "Authentication required" }),
      });
    }

    if (path === "/api/v1/auth/me") {
      return route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(mockUser),
      });
    }

    if (path === "/api/v1/auth/logout" && method === "POST") {
      signedIn = false;
      return route.fulfill({ status: 204 });
    }

    if (path === "/api/v1/auth/github" && method === "DELETE") {
      return route.fulfill({ status: 204 });
    }

    if (path === "/api/v1/dashboard") {
      return route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(
          empty
            ? { totalReviews: 0, issuesDetected: 0, criticalIssues: 0, activeRepositories: 0 }
            : { totalReviews: 3, issuesDetected: 2, criticalIssues: 1, activeRepositories: 1 },
        ),
      });
    }

    if (path === "/api/v1/repositories" && method === "GET") {
      return route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(pageOf(empty ? [] : mockRepos)),
      });
    }

    if (path === "/api/v1/repositories/sync" && method === "POST") {
      return route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(pageOf(mockRepos)),
      });
    }

    if (path.endsWith("/pull-requests") && method === "GET") {
      return route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(pageOf(empty ? [] : mockPullRequests)),
      });
    }

    if (path.endsWith("/pull-requests/sync") && method === "POST") {
      return route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(pageOf(mockPullRequests)),
      });
    }

    if (path.endsWith("/reviews") && path.startsWith("/api/v1/repositories/") && method === "POST") {
      return route.fulfill({
        status: 201,
        contentType: "application/json",
        body: JSON.stringify({
          id: "44444444-4444-4444-4444-444444444499",
          pullRequestId: mockPullRequests[0].id,
          jobId: "66666666-6666-6666-6666-666666666699",
          status: "QUEUED",
          createdAt: "2026-11-12T10:15:00Z",
        }),
      });
    }

    if (path === "/api/v1/reviews" && method === "GET") {
      return route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(pageOf(empty ? [] : mockReviews)),
      });
    }

    if (path.endsWith("/findings") && method === "GET") {
      const reviewId = path.split("/")[4];
      const findings = reviewId === mockReviews[0].id ? mockFindings : [];
      return route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(pageOf(findings)),
      });
    }

    return route.fulfill({
      status: 404,
      contentType: "application/json",
      body: JSON.stringify({ message: "Not found" }),
    });
  });
}

export const test = base.extend<{ consoleErrors: string[] }>({
  consoleErrors: async ({ page }, use) => {
    const errors: string[] = [];
    page.on("pageerror", (error) => errors.push(error.message));
    page.on("console", (msg) => {
      if (msg.type() === "error" && !msg.text().includes("401")) {
        errors.push(msg.text());
      }
    });
    await use(errors);
    expect(errors, "Unexpected browser console/page errors").toEqual([]);
  },
});

export { expect };
