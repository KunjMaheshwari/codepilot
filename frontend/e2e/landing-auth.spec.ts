import { expect, setupAuthenticatedApi, test } from "./fixtures";

test.describe("Landing Page & Authentication", () => {
  test("landing page renders positioning, workflow, and GitHub OAuth link", async ({ page, consoleErrors }) => {
    void consoleErrors;
    await page.goto("/");

    await expect(
      page.getByRole("heading", { name: /Evidence-based code reviews for every pull request/i }),
    ).toBeVisible();
    await expect(page.getByRole("link", { name: /Workflow/i }).first()).toBeVisible();
    await expect(page.getByRole("link", { name: /Security/i }).first()).toBeVisible();

    const oauthLink = page.getByRole("link", { name: /Sign in with GitHub/i }).first();
    await expect(oauthLink).toHaveAttribute("href", /\/oauth2\/authorization\/github$/);
  });

  test("unauthenticated user sees sign-in prompt and error recovery on dashboard", async ({ page }) => {
    await page.route("**/api/v1/**", (route) =>
      route.fulfill({
        status: 401,
        contentType: "application/json",
        body: JSON.stringify({ message: "Authentication required" }),
      }),
    );

    await page.goto("/dashboard");
    await expect(page.getByText(/Your session has expired\. Please sign in again\./i)).toBeVisible();
    await expect(page.getByRole("link", { name: /Sign in with GitHub/i })).toBeVisible();
  });

  test("authenticated user can sign out and loses access to protected data", async ({ page, consoleErrors }) => {
    void consoleErrors;
    await setupAuthenticatedApi(page);
    await page.goto("/dashboard/settings");

    await expect(page.getByText("octocat@example.com")).toBeVisible();
    await page.getByRole("button", { name: /Sign out/i }).click();

    await expect(page).toHaveURL(/\/$/);
    await page.goto("/dashboard");
    await expect(page.getByText(/Your session has expired\. Please sign in again\./i)).toBeVisible();
  });
});
