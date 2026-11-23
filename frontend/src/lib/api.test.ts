import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api, ApiError, apiErrorMessage } from "./api";

describe("api client error handling and CSRF behavior", () => {
  const fetchMock = vi.fn<typeof fetch>();

  beforeEach(() => {
    vi.stubGlobal("fetch", fetchMock);
    document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  });

  afterEach(() => {
    fetchMock.mockReset();
    vi.unstubAllGlobals();
  });

  it("throws ApiError with JSON error message on non-OK responses", async () => {
    fetchMock.mockResolvedValueOnce(
      new Response(JSON.stringify({ message: "Repository sync failed" }), {
        status: 502,
        headers: { "Content-Type": "application/json" },
      }),
    );

    await expect(api.getDashboard()).rejects.toMatchObject({
      name: "ApiError",
      status: 502,
      message: "Repository sync failed",
    });
  });

  it("falls back to status-based message for non-JSON error bodies", async () => {
    fetchMock.mockResolvedValueOnce(new Response("Bad Gateway", { status: 502 }));

    await expect(api.getMe()).rejects.toMatchObject({
      name: "ApiError",
      status: 502,
      message: "Request failed (502)",
    });
  });

  it("fetches CSRF token before POST, sends X-XSRF-TOKEN, and clears cached token on 403", async () => {
    // 1. CSRF bootstrap GET
    fetchMock.mockResolvedValueOnce(
      new Response(null, {
        status: 200,
        headers: { "X-XSRF-TOKEN": "csrf-token-1" },
      }),
    );
    // 2. First POST fails with 403 -> clears cached CSRF token
    fetchMock.mockResolvedValueOnce(
      new Response(JSON.stringify({ message: "Forbidden" }), {
        status: 403,
        headers: { "Content-Type": "application/json" },
      }),
    );

    await expect(api.syncRepositories()).rejects.toMatchObject({
      status: 403,
      message: "Your session has expired. Please sign in again.",
    });

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      expect.stringContaining("/api/v1/auth/csrf"),
      expect.objectContaining({ method: "GET", credentials: "include" }),
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      expect.stringContaining("/api/v1/repositories/sync"),
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({ "X-XSRF-TOKEN": "csrf-token-1" }),
      }),
    );

    // 3. Subsequent POST must re-fetch CSRF token since cache was cleared
    fetchMock.mockResolvedValueOnce(
      new Response(null, {
        status: 200,
        headers: { "X-XSRF-TOKEN": "csrf-token-2" },
      }),
    );
    fetchMock.mockResolvedValueOnce(
      new Response(JSON.stringify({ content: [], totalElements: 0, totalPages: 0, last: true }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      }),
    );

    const synced = await api.syncRepositories();
    expect(synced.content).toEqual([]);
    expect(fetchMock).toHaveBeenNthCalledWith(
      4,
      expect.stringContaining("/api/v1/repositories/sync"),
      expect.objectContaining({
        headers: expect.objectContaining({ "X-XSRF-TOKEN": "csrf-token-2" }),
      }),
    );
  });

  it("maps AbortError to 408 and TypeError to 401 ApiError", async () => {
    fetchMock.mockRejectedValueOnce(new DOMException("Aborted", "AbortError"));
    await expect(api.getDashboard()).rejects.toMatchObject({
      status: 408,
      message: "The request timed out. Please try again.",
    });

    fetchMock.mockRejectedValueOnce(new TypeError("Failed to fetch"));
    await expect(api.getDashboard()).rejects.toMatchObject({
      status: 401,
      message: "Your session has expired or the API is unreachable. Please sign in again.",
    });
  });

  it("formats error messages safely via apiErrorMessage", () => {
    expect(apiErrorMessage(new ApiError("Custom error", 400))).toBe("Custom error");
    expect(apiErrorMessage("unknown")).toBe("Something went wrong. Please try again.");
  });
});
