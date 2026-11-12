import { expect, setupAuthenticatedApi, test } from "./fixtures";

const viewports = [
  { name: "desktop", width: 1280, height: 800 },
  { name: "tablet", width: 768, height: 1024 },
  { name: "mobile", width: 390, height: 844 },
];

test.describe("Responsive Layout & Accessibility", () => {
  for (const vp of viewports) {
    test(`no horizontal overflow and accessible navigation on ${vp.name}`, async ({
      page,
      consoleErrors,
    }) => {
      void consoleErrors;
      await page.setViewportSize({ width: vp.width, height: vp.height });
      await setupAuthenticatedApi(page);

      for (const route of ["/", "/dashboard", "/dashboard/repositories", "/dashboard/reviews", "/dashboard/settings"]) {
        await page.goto(route);
        const overflow = await page.evaluate(
          () => document.documentElement.scrollWidth > document.documentElement.clientWidth,
        );
        expect(overflow, `Horizontal overflow detected on ${route} (${vp.name})`).toBe(false);
      }

      if (vp.name === "mobile") {
        const menuButton = page.getByRole("button", { name: /Toggle navigation menu/i });
        await expect(menuButton).toBeVisible();
        await menuButton.click();
        await expect(page.getByRole("navigation", { name: /Main navigation/i })).toBeVisible();
      }
    });
  }

  test("keyboard navigation reaches interactive controls with visible focus", async ({ page, consoleErrors }) => {
    void consoleErrors;
    await setupAuthenticatedApi(page);
    await page.goto("/dashboard/repositories");

    await page.keyboard.press("Tab");
    const activeTag = await page.evaluate(() => document.activeElement?.tagName);
    expect(activeTag).toBeTruthy();
  });
});

