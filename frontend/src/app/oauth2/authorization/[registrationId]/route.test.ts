import { NextRequest } from "next/server";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { GET } from "./route";

describe("GET /oauth2/authorization/[registrationId]", () => {
  const fetchMock = vi.fn<typeof fetch>();

  beforeEach(() => {
    vi.stubGlobal("fetch", fetchMock);
    process.env.BACKEND_URL = "https://backend.example.com";
  });

  afterEach(() => {
    fetchMock.mockReset();
    vi.unstubAllGlobals();
    delete process.env.BACKEND_URL;
  });

  it("preserves 302 GitHub redirect, forwarded headers, and Set-Cookie headers", async () => {
    const upstreamHeaders = new Headers({
      location: "https://github.com/login/oauth/authorize?client_id=test&state=abc",
    });
    upstreamHeaders.append("set-cookie", "JSESSIONID=sess123; Path=/; Secure; HttpOnly; SameSite=Lax");
    upstreamHeaders.append("set-cookie", "XSRF-TOKEN=xsrf123; Path=/; Secure; SameSite=Lax");

    fetchMock.mockResolvedValueOnce(
      new Response(null, { status: 302, headers: upstreamHeaders }),
    );

    const request = new NextRequest("https://codepilot-wi3s.vercel.app/oauth2/authorization/github", {
      headers: {
        host: "codepilot-wi3s.vercel.app",
        "x-forwarded-proto": "https",
      },
    });

    const response = await GET(request, { params: Promise.resolve({ registrationId: "github" }) });

    expect(response.status).toBe(302);
    expect(response.headers.get("location")).toBe(
      "https://github.com/login/oauth/authorize?client_id=test&state=abc",
    );
    expect(response.headers.getSetCookie()).toEqual([
      "JSESSIONID=sess123; Path=/; Secure; HttpOnly; SameSite=Lax",
      "XSRF-TOKEN=xsrf123; Path=/; Secure; SameSite=Lax",
    ]);
    expect(fetchMock).toHaveBeenCalledWith(
      "https://backend.example.com/oauth2/authorization/github",
      expect.objectContaining({
        method: "GET",
        redirect: "manual",
      }),
    );
  });

  it("intercepts Render hibernation 503 interstitial and returns CodePilot 503 recovery page", async () => {
    fetchMock.mockResolvedValueOnce(
      new Response("<!doctype html><title>Render - Application loading</title>", {
        status: 503,
        headers: {
          "content-type": "text/html; charset=utf-8",
          "x-render-routing": "hibernate-pending-wake",
          "retry-after": "5",
        },
      }),
    );

    const request = new NextRequest("https://codepilot-wi3s.vercel.app/oauth2/authorization/github");
    const response = await GET(request, { params: Promise.resolve({ registrationId: "github" }) });
    const html = await response.text();

    expect(response.status).toBe(503);
    expect(response.headers.get("retry-after")).toBe("5");
    expect(html).toContain("Starting CodePilot sign-in service");
    expect(html).not.toContain("Render - Application loading");
  });

  it("returns 503 recovery page on upstream network/timeout error", async () => {
    fetchMock.mockRejectedValueOnce(new TypeError("fetch failed"));

    const request = new NextRequest("https://codepilot-wi3s.vercel.app/oauth2/authorization/github");
    const response = await GET(request, { params: Promise.resolve({ registrationId: "github" }) });

    expect(response.status).toBe(503);
    expect(response.headers.get("retry-after")).toBe("5");
  });
});
