import { expect, test } from "@playwright/test";

const hasStorageState = Boolean(process.env.PLAYWRIGHT_STORAGE_STATE);

test.describe("Production Authenticated Workflows Audit", () => {
  test.skip(
    !hasStorageState,
    "BLOCKED: PLAYWRIGHT_STORAGE_STATE not provided (interactive GitHub OAuth session required for authenticated production workflows)",
  );

  test("authenticated dashboard renders live user profile and statistics", async ({ page }) => {
    const meResponsePromise = page.waitForResponse(
      (res) => res.url().includes("/api/v1/auth/me") && res.request().method() === "GET",
    );

    await page.goto("/dashboard", { waitUntil: "networkidle" });
    const meRes = await meResponsePromise;
    expect(meRes.status()).toBe(200);

    const profile = (await meRes.json()) as { username?: string };
    expect(profile.username).toBeTruthy();
    await expect(page.locator("header")).toContainText(profile.username ?? "");
    await expect(page.getByText(/Your session has expired/i)).toHaveCount(0);
  });

  test("repository synchronization completes without timeout and updates repositories", async ({
    page,
  }) => {
    await page.goto("/dashboard/repositories", { waitUntil: "networkidle" });

    const syncButton = page.getByRole("button", { name: /Synchronize GitHub/i }).first();
    await expect(syncButton).toBeVisible();

    const syncResponsePromise = page.waitForResponse(
      (res) => res.url().includes("/api/v1/repositories/sync") && res.request().method() === "POST",
      { timeout: 60_000 },
    );

    await syncButton.click();
    const syncRes = await syncResponsePromise;
    expect(syncRes.status()).toBe(200);
    await expect(page.getByText(/The request timed out/i)).toHaveCount(0);
  });

  test("reviews page loads without false expired-session banner", async ({ page }) => {
    const reviewsResPromise = page.waitForResponse(
      (res) => res.url().includes("/api/v1/reviews") && res.request().method() === "GET",
    );

    await page.goto("/dashboard/reviews", { waitUntil: "networkidle" });
    const reviewsRes = await reviewsResPromise;
    expect(reviewsRes.status()).toBe(200);
    await expect(page.getByText(/Your session has expired/i)).toHaveCount(0);
  });
});
