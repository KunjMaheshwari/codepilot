import { expect, mockPullRequests, mockRepos, setupAuthenticatedApi, test } from "./fixtures";

test.describe("Dashboard, Repositories & Pull Requests", () => {
  test("dashboard renders real KPIs, recent reviews, and navigates across sections", async ({ page, consoleErrors }) => {
    void consoleErrors;
    await setupAuthenticatedApi(page);
    await page.goto("/dashboard");

    await expect(page.getByRole("heading", { name: "Overview" })).toBeVisible();
    await expect(page.getByText("Total Reviews")).toBeVisible();
    await expect(page.getByText("Critical Issues")).toBeVisible();
    await expect(page.getByText("review/44444444").first()).toBeVisible();

    await page.getByRole("link", { name: "Repositories" }).first().click();
    await expect(page).toHaveURL(/\/dashboard\/repositories/);
  });

  test("dashboard renders empty states and retryable API error state", async ({ page }) => {
    await setupAuthenticatedApi(page, { empty: true });
    await page.goto("/dashboard");

    await expect(page.getByText("No review activity in the last 7 days")).toBeVisible();
    await expect(page.getByText("No reviews yet")).toBeVisible();

    await page.unroute("**/api/v1/**");
    await page.route("**/api/v1/**", (route) =>
      route.fulfill({
        status: 500,
        contentType: "application/json",
        body: JSON.stringify({ message: "Database temporarily unavailable" }),
      }),
    );

    await page.getByRole("button", { name: "Refresh" }).click();
    await expect(page.getByText("Database temporarily unavailable")).toBeVisible();
    await expect(page.getByRole("button", { name: "Retry" })).toBeVisible();
  });

  test("repository search, active/inactive filtering, sync, and PR manual review flow", async ({
    page,
    consoleErrors,
  }) => {
    void consoleErrors;
    await setupAuthenticatedApi(page);
    let reviewPostCount = 0;

    await page.route(`**/api/v1/repositories/${mockRepos[0].id}/reviews`, async (route) => {
      reviewPostCount += 1;
      await route.fulfill({
        status: 201,
        contentType: "application/json",
        body: JSON.stringify({
          id: "44444444-4444-4444-4444-444444444499",
          pullRequestId: mockPullRequests[0].id,
          jobId: "66666666-6666-6666-6666-666666666699",
          status: "QUEUED",
        }),
      });
    });

    await page.goto("/dashboard/repositories");

    await expect(page.getByText("octocat/codepilot-api")).toBeVisible();
    await expect(page.getByText("octocat/legacy-cli")).toBeVisible();

    await page.getByRole("button", { name: "inactive", exact: true }).click();
    await expect(page.getByText("octocat/legacy-cli")).toBeVisible();
    await expect(page.getByText("octocat/codepilot-api")).toBeHidden();

    await page.getByRole("button", { name: "all", exact: true }).click();
    await page.getByLabel("Search repositories").fill("codepilot");
    await expect(page.getByText("octocat/codepilot-api")).toBeVisible();
    await expect(page.getByText("octocat/legacy-cli")).toBeHidden();

    await page.getByRole("button", { name: /Synchronize GitHub/i }).click();
    await expect(page.getByText("Repositories synchronized")).toBeVisible();

    await page.getByRole("button", { name: /octocat\/codepilot-api/i }).click();
    await expect(page.getByText("#42")).toBeVisible();
    await expect(page.getByText("Harden OAuth session rotation")).toBeVisible();

    // Closed and merged PRs have disabled Review buttons
    const reviewButtons = page.getByRole("button", { name: "Review", exact: true });
    await expect(reviewButtons.nth(0)).toBeEnabled();
    await expect(reviewButtons.nth(1)).toBeDisabled();
    await expect(reviewButtons.nth(2)).toBeDisabled();

    // Sync Pull Requests
    await page.getByRole("button", { name: /Sync Pull Requests/i }).click();
    await expect(page.getByText("Pull requests synchronized")).toBeVisible();

    // Trigger manual review -> switches to Inspect Review (prevents duplicate clicks)
    await reviewButtons.nth(0).click();
    await expect(page.getByRole("link", { name: "Inspect Review" })).toBeVisible();
    expect(reviewPostCount).toBe(1);
  });

  test("loads all repository pages (>100 repositories) and finds repositories beyond the first page", async ({
    page,
    consoleErrors,
  }) => {
    void consoleErrors;
    await setupAuthenticatedApi(page);

    const totalRepos = 273;
    const allRepos = Array.from({ length: totalRepos }, (_, i) => {
      const isLeetCode = i === 158;
      return {
        id: `22222222-2222-2222-2222-${String(i).padStart(12, "0")}`,
        name: isLeetCode ? "LeetCode" : `repo-${i}`,
        fullName: isLeetCode ? "KunjMaheshwari/LeetCode" : `KunjMaheshwari/repo-${i}`,
        description: isLeetCode ? "DSA solutions" : `Repository ${i}`,
        githubRepoId: String(810000000 + i),
        isActive: true,
        isPrivate: false,
        defaultBranch: "main",
        htmlUrl: isLeetCode
          ? "https://github.com/KunjMaheshwari/LeetCode"
          : `https://github.com/KunjMaheshwari/repo-${i}`,
        lastSyncedAt: "2026-11-13T05:13:57Z",
      };
    });

    await page.route("**/api/v1/repositories**", async (route) => {
      const url = new URL(route.request().url());
      if (url.pathname === "/api/v1/repositories" && route.request().method() === "GET") {
        const pageNum = Number(url.searchParams.get("page") ?? "0");
        const size = Number(url.searchParams.get("size") ?? "20");
        const start = pageNum * size;
        const slice = allRepos.slice(start, start + size);
        const totalPages = Math.ceil(totalRepos / size);
        return route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify({
            content: slice,
            pageNumber: pageNum,
            pageSize: size,
            totalElements: totalRepos,
            totalPages,
            last: pageNum + 1 >= totalPages,
          }),
        });
      }
      if (url.pathname === "/api/v1/repositories/sync" && route.request().method() === "POST") {
        const slice = allRepos.slice(0, 100);
        return route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify({
            content: slice,
            pageNumber: 0,
            pageSize: 100,
            totalElements: totalRepos,
            totalPages: 3,
            last: false,
          }),
        });
      }
      return route.fallback();
    });

    await page.goto("/dashboard/repositories");

    await page.getByLabel("Search repositories").fill("LeetCode");
    await expect(page.getByText("KunjMaheshwari/LeetCode")).toBeVisible();

    await page.getByRole("button", { name: /Synchronize GitHub/i }).click();
    await expect(page.getByText("Loaded 273 repositories from GitHub.")).toBeVisible();
    await expect(page.getByText("KunjMaheshwari/LeetCode")).toBeVisible();
  });
});
