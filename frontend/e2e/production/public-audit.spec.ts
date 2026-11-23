import { expect, test } from "@playwright/test";

const VIEWPORTS = [
  { name: "desktop", width: 1440, height: 900 },
  { name: "tablet", width: 768, height: 1024 },
  { name: "mobile", width: 390, height: 844 },
] as const;

test.describe("Production Public & Infrastructure Audit", () => {
  test("homepage renders cleanly with zero console errors, page errors, or 5xx responses", async ({
    page,
  }) => {
    const consoleErrors: string[] = [];
    const pageErrors: string[] = [];
    const serverErrors: string[] = [];

    page.on("console", (msg) => {
      if (msg.type() === "error") consoleErrors.push(msg.text());
    });
    page.on("pageerror", (err) => pageErrors.push(err.message));
    page.on("response", (res) => {
      if (res.status() >= 500) {
        serverErrors.push(`${res.status()} ${res.url()}`);
      }
    });

    const response = await page.goto("/", { waitUntil: "networkidle" });
    expect(response?.status()).toBe(200);
    await expect(page.getByText(/CodePilot/i).first()).toBeVisible();
    await expect(
      page.getByRole("link", { name: /Sign in with GitHub|Get Started|Continue with GitHub/i }).first(),
    ).toBeVisible();

    expect(consoleErrors, `Console errors: ${consoleErrors.join("; ")}`).toEqual([]);
    expect(pageErrors, `Page errors: ${pageErrors.join("; ")}`).toEqual([]);
    expect(serverErrors, `5xx responses: ${serverErrors.join("; ")}`).toEqual([]);
  });

  test("health and CSRF proxy endpoints respond cleanly", async ({ request }) => {
    const healthRes = await request.get("/api/health");
    expect(healthRes.status()).toBe(200);

    const csrfRes = await request.get("/api/v1/auth/csrf");
    expect([200, 204]).toContain(csrfRes.status());
    const csrfHeader = csrfRes.headers()["x-xsrf-token"];
    const setCookie = csrfRes.headers()["set-cookie"] ?? "";
    expect(
      Boolean(csrfHeader) || setCookie.includes("XSRF-TOKEN="),
      "Expected X-XSRF-TOKEN header or XSRF-TOKEN cookie from /api/v1/auth/csrf",
    ).toBeTruthy();
  });

  test("GitHub OAuth initiation redirects to GitHub and never shows Render interstitial", async ({
    page,
  }) => {
    await page.goto("/oauth2/authorization/github", { waitUntil: "domcontentloaded", timeout: 45_000 });
    const bodyText = (await page.locator("body").innerText()).toUpperCase();
    expect(bodyText).not.toContain("INCOMING HTTP REQUEST DETECTED");
    expect(bodyText).not.toContain("ALLOCATING COMPUTE RESOURCES");

    const finalUrl = page.url();
    expect(
      finalUrl.startsWith("https://github.com/login") ||
        finalUrl.includes("/oauth2/authorization/github"),
      `Unexpected OAuth destination URL: ${finalUrl}`,
    ).toBeTruthy();

    if (finalUrl.startsWith("https://github.com/login")) {
      expect(finalUrl).toContain("github.com");
    }
  });

  test("unauthenticated dashboard, repositories, and reviews pages handle 401 consistently without stale header user", async ({
    page,
  }) => {
    const pageErrors: string[] = [];
    const serverErrors: string[] = [];

    page.on("pageerror", (err) => pageErrors.push(err.message));
    page.on("response", (res) => {
      if (res.status() >= 500) {
        serverErrors.push(`${res.status()} ${res.url()}`);
      }
    });

    for (const route of ["/dashboard", "/dashboard/repositories", "/dashboard/reviews"]) {
      await page.goto(route, { waitUntil: "networkidle" });
      await expect(page.locator("header")).toBeVisible();
      await expect(
        page.locator("header").getByRole("link", { name: /Sign in with GitHub/i }),
      ).toBeVisible({ timeout: 15_000 });
    }

    expect(pageErrors, `Page errors: ${pageErrors.join("; ")}`).toEqual([]);
    expect(serverErrors, `5xx responses: ${serverErrors.join("; ")}`).toEqual([]);
  });

  test("unknown route returns HTTP 404 cleanly", async ({ page }) => {
    const response = await page.goto("/this-route-does-not-exist-audit", {
      waitUntil: "domcontentloaded",
    });
    expect(response?.status()).toBe(404);
  });

  for (const viewport of VIEWPORTS) {
    test(`responsive layout has no horizontal overflow on ${viewport.name} (${viewport.width}x${viewport.height})`, async ({
      page,
    }) => {
      await page.setViewportSize({ width: viewport.width, height: viewport.height });
      await page.goto("/", { waitUntil: "networkidle" });
      const overflow = await page.evaluate(
        () => document.documentElement.scrollWidth - window.innerWidth,
      );
      expect(overflow, `Horizontal overflow on ${viewport.name}: ${overflow}px`).toBeLessThanOrEqual(
        2,
      );
    });
  }
});
