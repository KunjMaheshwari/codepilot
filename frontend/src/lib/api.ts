export const API_BASE_URL =
  process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  pageNumber?: number;
  pageSize?: number;
  number?: number;
  size?: number;
  first?: boolean;
  last: boolean;
}

export interface Repository {
  id: string;
  name: string;
  fullName: string;
  description?: string;
  githubRepoId: string;
  isActive: boolean;
  isPrivate: boolean;
  defaultBranch?: string;
  htmlUrl?: string;
  lastSyncedAt?: string;
}

export interface PullRequest {
  id: string;
  repositoryId: string;
  prNumber: number;
  title: string;
  author: string;
  status: string;
  baseBranch?: string;
  headBranch?: string;
  baseSha?: string;
  headSha?: string;
  additions?: number;
  deletions?: number;
  changedFiles?: number;
  createdAt?: string;
  updatedAt?: string;
}

export interface Review {
  id: string;
  pullRequestId: string;
  jobId?: string;
  status: string;
  startedAt?: string;
  completedAt?: string;
  createdAt?: string;
}

export interface ReviewFinding {
  id: string;
  filePath: string;
  lineNumber?: number;
  severity: string;
  title?: string;
  description?: string;
  category?: string;
  evidence?: string;
  impact?: string;
  suggestedFix?: string;
  confidence?: string;
  message: string;
}

export interface ReviewJob {
  jobId: string;
  reviewId: string;
  status: string;
  attemptCount: number;
  createdAt?: string;
  startedAt?: string;
  completedAt?: string;
  errorMessage?: string;
  errorCategory?: string;
}

export interface DashboardStats {
  totalReviews: number;
  issuesDetected: number;
  criticalIssues: number;
  activeRepositories: number;
}

export interface UserProfile {
  id: string;
  username: string;
  email: string;
  createdAt?: string;
}

type RequestOptions = Omit<RequestInit, "body"> & {
  body?: unknown;
  timeoutMs?: number;
};

const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS", "TRACE"]);
const DEFAULT_TIMEOUT_MS = 15000;
const SYNC_TIMEOUT_MS = 60000;

const sessionExpiredListeners = new Set<() => void>();

export function onSessionExpired(listener: () => void): () => void {
  sessionExpiredListeners.add(listener);
  return () => {
    sessionExpiredListeners.delete(listener);
  };
}

function notifySessionExpired() {
  for (const listener of sessionExpiredListeners) {
    try {
      listener();
    } catch {
      // Never let a UI listener break API error propagation.
    }
  }
}

function readCookie(name: string): string | undefined {
  if (typeof document === "undefined") return undefined;
  const prefix = `${name}=`;
  const match = document.cookie
    .split(";")
    .map((part) => part.trim())
    .find((part) => part.startsWith(prefix));
  return match ? decodeURIComponent(match.slice(prefix.length)) : undefined;
}

let cachedCsrfToken: string | undefined;

async function ensureCsrfToken(): Promise<string | undefined> {
  const existing = readCookie("XSRF-TOKEN");
  if (existing) {
    cachedCsrfToken = existing;
    return existing;
  }
  if (cachedCsrfToken) return cachedCsrfToken;
  const response = await fetch(`${API_BASE_URL}/api/v1/auth/csrf`, {
    method: "GET",
    credentials: "include",
  });
  const fromCookie = readCookie("XSRF-TOKEN");
  const fromHeader = response.headers.get("X-XSRF-TOKEN") ?? undefined;
  cachedCsrfToken = fromCookie ?? fromHeader;
  return cachedCsrfToken;
}

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { timeoutMs = DEFAULT_TIMEOUT_MS, body, ...fetchOptions } = options;
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), timeoutMs);
  try {
    const method = (fetchOptions.method ?? "GET").toUpperCase();
    const csrfToken = SAFE_METHODS.has(method) ? undefined : await ensureCsrfToken();

    const response = await fetch(`${API_BASE_URL}${path}`, {
      ...fetchOptions,
      method,
      credentials: "include",
      headers: {
        Accept: "application/json",
        ...(body === undefined ? {} : { "Content-Type": "application/json" }),
        ...(csrfToken ? { "X-XSRF-TOKEN": csrfToken } : {}),
        ...fetchOptions.headers,
      },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal,
    });

    if (!response.ok) {
      let message = `Request failed (${response.status})`;
      const renderRouting = response.headers.get("x-render-routing") ?? "";
      const contentType = response.headers.get("content-type") ?? "";
      if (
        response.status === 503 &&
        (renderRouting.toLowerCase().includes("hibernate") || contentType.toLowerCase().includes("text/html"))
      ) {
        throw new ApiError("Backend service is waking up. Please retry in a few seconds.", 503);
      }
      let backendMessage: string | undefined;
      try {
        const error = (await response.json()) as { message?: string };
        if (error.message && error.message.trim().length > 0) {
          backendMessage = error.message;
          message = error.message;
        }
      } catch {
        // Keep the status-based message for non-JSON responses.
      }
      if (response.status === 401) {
        cachedCsrfToken = undefined;
        notifySessionExpired();
        message = "Your session has expired. Please sign in again.";
      } else if (response.status === 403) {
        cachedCsrfToken = undefined;
        message =
          backendMessage && backendMessage.toLowerCase() !== "forbidden"
            ? backendMessage
            : "Access denied or security token expired. Please refresh and try again.";
      }
      throw new ApiError(message, response.status);
    }
    if (response.status === 204) return undefined as T;
    return (await response.json()) as T;
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") {
      throw new ApiError("The request timed out. Please try again.", 408);
    }
    if (error instanceof TypeError) {
      throw new ApiError("Unable to reach the CodePilot API. Please check your connection and try again.", 0);
    }
    throw error;
  } finally {
    window.clearTimeout(timeout);
  }
}

export const api = {
  getDashboard: () => request<DashboardStats>("/api/v1/dashboard"),
  getRepositories: (page = 0, size = 20) =>
    request<Page<Repository>>(`/api/v1/repositories?page=${page}&size=${size}`),
  getAllRepositories: async (pageSize = 100): Promise<Repository[]> => {
    const byId = new Map<string, Repository>();
    let page = 0;
    while (page < 1000) {
      const result = await api.getRepositories(page, pageSize);
      for (const repo of result.content) {
        byId.set(repo.id, repo);
      }
      if (
        result.last ||
        result.content.length === 0 ||
        (result.totalPages > 0 && page + 1 >= result.totalPages)
      ) {
        break;
      }
      page += 1;
    }
    return Array.from(byId.values());
  },
  syncRepositories: () =>
    request<Page<Repository>>("/api/v1/repositories/sync", {
      method: "POST",
      timeoutMs: SYNC_TIMEOUT_MS,
    }),
  getPullRequests: (repositoryId: string, page = 0, size = 20) =>
    request<Page<PullRequest>>(
      `/api/v1/repositories/${repositoryId}/pull-requests?page=${page}&size=${size}`,
    ),
  syncPullRequests: (repositoryId: string, page = 0, size = 20) =>
    request<Page<PullRequest>>(
      `/api/v1/repositories/${repositoryId}/pull-requests/sync?page=${page}&size=${size}`,
      { method: "POST", timeoutMs: SYNC_TIMEOUT_MS },
    ),
  getReviews: (page = 0, size = 20) =>
    request<Page<Review>>(`/api/v1/reviews?page=${page}&size=${size}`),
  getReview: (reviewId: string) => request<Review>(`/api/v1/reviews/${reviewId}`),
  createReview: (repositoryId: string, pullRequestId: string) =>
    request<Review>(`/api/v1/repositories/${repositoryId}/reviews`, {
      method: "POST",
      body: { pullRequestId },
    }),
  getJob: (reviewId: string) =>
    request<ReviewJob>(`/api/v1/reviews/${reviewId}/job`),
  getFindings: (reviewId: string, page = 0, size = 100) =>
    request<Page<ReviewFinding>>(
      `/api/v1/reviews/${reviewId}/findings?page=${page}&size=${size}`,
    ),
  getMe: () => request<UserProfile>("/api/v1/auth/me"),
  logout: async () => {
    try {
      await request<void>("/api/v1/auth/logout", { method: "POST" });
    } finally {
      cachedCsrfToken = undefined;
    }
  },
  disconnectGitHub: async () => {
    try {
      await request<void>("/api/v1/auth/github", { method: "DELETE" });
    } finally {
      cachedCsrfToken = undefined;
    }
  },
};

export const githubLoginUrl = `${API_BASE_URL}/oauth2/authorization/github`;

export function formatDate(value?: string) {
  return value ? new Date(value).toLocaleString() : "—";
}

export function apiErrorMessage(error: unknown) {
  return error instanceof Error ? error.message : "Something went wrong. Please try again.";
}
