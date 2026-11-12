import { expect, mockFindings, mockReviews, pageOf, setupAuthenticatedApi, test } from "./fixtures";

test.describe("Reviews Workspace & Settings", () => {
  test("completed reviews load findings automatically with severity filters and prev/next navigation", async ({
    page,
    consoleErrors,
  }) => {
    void consoleErrors;
    await setupAuthenticatedApi(page);
    await page.goto("/dashboard/reviews");

    await expect(page.getByRole("heading", { name: "Reviews & Findings" })).toBeVisible();
    // Completed review auto-loads findings
    await expect(
      page.getByRole("heading", { name: "Session fixation risk on OAuth callback" }),
    ).toBeVisible();
    await expect(page.getByText("src/auth/SessionFilter.java:42").first()).toBeVisible();
    await expect(page.getByText("request.changeSessionId();")).toBeVisible();
    await expect(page.getByText("1 / 2")).toBeVisible();

    // Next finding navigation
    await page.getByRole("button", { name: "Next finding" }).click();
    await expect(page.getByText("2 / 2")).toBeVisible();
    await expect(page.getByRole("heading", { name: "Unbounded in-memory map growth" })).toBeVisible();

    // Previous finding navigation
    await page.getByRole("button", { name: "Previous finding" }).click();
    await expect(page.getByText("1 / 2")).toBeVisible();

    // Severity filter
    await page.getByRole("button", { name: "MEDIUM (1)" }).click();
    await expect(page.getByText("1 / 1")).toBeVisible();
    await expect(page.getByRole("heading", { name: "Unbounded in-memory map growth" })).toBeVisible();

    // Partial and Failed review states
    await page.getByRole("button", { name: /review\/44444444/i }).nth(1).click();
    await expect(page.getByText(/Partial review:/i)).toBeVisible();

    await page.getByRole("button", { name: /review\/44444444/i }).nth(2).click();
    await expect(page.getByText(/This review job failed/i)).toBeVisible();
  });

  test("active review polls job status and loads findings on completion", async ({ page, consoleErrors }) => {
    void consoleErrors;
    await setupAuthenticatedApi(page);

    let pollCount = 0;
    const queuedReview = {
      id: "44444444-4444-4444-4444-444444444477",
      pullRequestId: "33333333-3333-3333-3333-333333333331",
      status: "QUEUED",
      createdAt: "2026-11-12T10:15:00Z",
    };

    await page.route("**/api/v1/reviews?*", (route) =>
      route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(pageOf([pollCount >= 1 ? { ...queuedReview, status: "COMPLETED" } : queuedReview])),
      }),
    );

    await page.route(`**/api/v1/reviews/${queuedReview.id}/job`, (route) => {
      pollCount += 1;
      return route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          jobId: "66666666-6666-6666-6666-666666666677",
          reviewId: queuedReview.id,
          status: "COMPLETED",
          attemptCount: 1,
        }),
      });
    });

    await page.route(`**/api/v1/reviews/${queuedReview.id}/findings*`, (route) =>
      route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(pageOf(mockFindings)),
      }),
    );

    await page.goto("/dashboard/reviews");
    await expect(page.getByText(/Review job is currently queued/i)).toBeVisible();
    // Wait for 5s polling interval to transition to COMPLETED and load findings
    await expect(
      page.getByRole("heading", { name: "Session fixation risk on OAuth callback" }),
    ).toBeVisible({ timeout: 10_000 });
  });

  test("settings displays account details and disconnect confirmation dialog", async ({ page, consoleErrors }) => {
    void consoleErrors;
    await setupAuthenticatedApi(page);
    await page.goto("/dashboard/settings");

    await expect(page.getByText("octocat", { exact: true }).first()).toBeVisible();
    await expect(page.getByText("octocat@example.com")).toBeVisible();
    await expect(page.getByText("Linked", { exact: true })).toBeVisible();

    await page.getByRole("button", { name: /Disconnect GitHub/i }).click();
    const dialog = page.getByRole("dialog");
    await expect(dialog).toBeVisible();
    await expect(dialog).toContainText(/Disconnect GitHub account\?/i);

    await dialog.getByRole("button", { name: /Confirm Disconnect/i }).click();
    await expect(page.getByText("GitHub disconnected")).toBeVisible();
    await expect(page.getByRole("button", { name: /Reconnect GitHub/i })).toBeVisible();
    void mockReviews;
  });
});
