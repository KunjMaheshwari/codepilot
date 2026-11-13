"use client";

import Link from "next/link";
import { useCallback, useEffect, useMemo, useState } from "react";
import {
  ArrowLeft,
  CheckCircle2,
  ChevronLeft,
  ChevronRight,
  CircleDashed,
  FileCode2,
  GitBranch,
  History,
  RefreshCw,
  Search,
  XCircle,
} from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { EmptyState, ErrorBanner } from "@/components/ui/empty-state";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { StatusBadge } from "@/components/ui/status-badge";
import {
  api,
  apiErrorMessage,
  formatDate,
  type Review,
  type ReviewFinding,
  type ReviewJob,
} from "@/lib/api";

const activeStatuses = new Set(["PENDING", "QUEUED", "PROCESSING", "RETRYING"]);
const terminalWithFindings = new Set(["COMPLETED", "PARTIAL"]);
const severityOrder = ["CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO"] as const;

type ReviewFilter = "all" | "active" | "completed" | "failed";

function failureCategoryLabel(category?: string): string | null {
  switch (category) {
    case "EMPTY_DIFF":
      return "Empty pull request diff";
    case "NO_REVIEWABLE_FILES":
      return "No supported source files";
    case "DIFF_TOO_LARGE":
      return "Diff exceeds size limit";
    case "GITHUB_API_ERROR":
      return "GitHub API failure";
    case "GEMINI_API_ERROR":
      return "Gemini API failure";
    case "INVALID_AI_RESPONSE":
      return "Invalid AI response";
    case "RETRY_EXHAUSTED":
      return "Retry exhaustion";
    default:
      return null;
  }
}

function formatFailureBanner(job?: { errorCategory?: string; errorMessage?: string }): string {
  const label = failureCategoryLabel(job?.errorCategory);
  const detail = job?.errorMessage?.trim();
  if (label && detail) {
    return `${label}: ${detail}`;
  }
  if (label) {
    return label;
  }
  if (detail) {
    return detail;
  }
  return "This review job failed before findings could be finalized.";
}

export default function ReviewsPage() {
  const [reviews, setReviews] = useState<Review[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [jobs, setJobs] = useState<Record<string, ReviewJob>>({});
  const [findingsByReview, setFindingsByReview] = useState<Record<string, ReviewFinding[]>>({});
  const [loadingFindingsId, setLoadingFindingsId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [reviewFilter, setReviewFilter] = useState<ReviewFilter>("all");
  const [query, setQuery] = useState("");
  const [severityFilter, setSeverityFilter] = useState<string>("ALL");
  const [activeFindingIndex, setActiveFindingIndex] = useState(0);

  const loadReviews = useCallback(async () => {
    setError("");
    try {
      const page = await api.getReviews(0, 100);
      setReviews(page.content);
      setSelectedId((current) => {
        if (current && page.content.some((r) => r.id === current)) {
          return current;
        }
        const paramId =
          typeof window !== "undefined"
            ? new URLSearchParams(window.location.search).get("reviewId")
            : null;
        if (paramId && page.content.some((r) => r.id === paramId)) {
          return paramId;
        }
        return page.content[0]?.id ?? null;
      });
    } catch (reason: unknown) {
      setError(apiErrorMessage(reason));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    api
      .getReviews(0, 100)
      .then((page) => {
        if (cancelled) return;
        setReviews(page.content);
        setSelectedId((current) => {
          if (current && page.content.some((r) => r.id === current)) {
            return current;
          }
          const paramId =
            typeof window !== "undefined"
              ? new URLSearchParams(window.location.search).get("reviewId")
              : null;
          if (paramId && page.content.some((r) => r.id === paramId)) {
            return paramId;
          }
          return page.content[0]?.id ?? null;
        });
      })
      .catch((reason: unknown) => {
        if (!cancelled) setError(apiErrorMessage(reason));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const loadFindings = useCallback(async (reviewId: string) => {
    setLoadingFindingsId(reviewId);
    try {
      const page = await api.getFindings(reviewId, 0, 100);
      setFindingsByReview((current) => ({ ...current, [reviewId]: page.content }));
    } catch (reason: unknown) {
      setError(apiErrorMessage(reason));
    } finally {
      setLoadingFindingsId((current) => (current === reviewId ? null : current));
    }
  }, []);

  // Poll active review jobs every 5s
  useEffect(() => {
    const active = reviews.filter((review) =>
      activeStatuses.has(jobs[review.id]?.status ?? review.status),
    );
    if (active.length === 0) return;

    const timer = window.setInterval(() => {
      Promise.all(
        active.map((review) =>
          api.getJob(review.id).then((job) => {
            setJobs((current) => ({ ...current, [review.id]: job }));
            if (!activeStatuses.has(job.status)) {
              if (terminalWithFindings.has(job.status)) {
                void loadFindings(review.id);
              }
              void loadReviews();
            }
          }),
        ),
      ).catch((reason: unknown) => setError(apiErrorMessage(reason)));
    }, 5000);

    return () => window.clearInterval(timer);
  }, [reviews, jobs, loadFindings, loadReviews]);

  const selectedReview = useMemo(
    () => reviews.find((review) => review.id === selectedId) ?? null,
    [reviews, selectedId],
  );

  const selectedStatus = selectedReview
    ? (jobs[selectedReview.id]?.status ?? selectedReview.status)
    : null;

  // Automatically load findings when selecting a completed/partial review
  useEffect(() => {
    if (!selectedReview || !selectedStatus) return;
    if (!terminalWithFindings.has(selectedStatus) || findingsByReview[selectedReview.id]) {
      return;
    }
    let cancelled = false;
    api
      .getFindings(selectedReview.id, 0, 100)
      .then((page) => {
        if (!cancelled) {
          setFindingsByReview((current) => ({ ...current, [selectedReview.id]: page.content }));
        }
      })
      .catch((reason: unknown) => {
        if (!cancelled) setError(apiErrorMessage(reason));
      });
    return () => {
      cancelled = true;
    };
  }, [selectedReview, selectedStatus, findingsByReview]);

  const filteredReviews = useMemo(() => {
    const normalizedQuery = query.trim().toLowerCase();
    return reviews.filter((review) => {
      const effectiveStatus = jobs[review.id]?.status ?? review.status;
      if (reviewFilter === "active" && !activeStatuses.has(effectiveStatus)) return false;
      if (reviewFilter === "completed" && !terminalWithFindings.has(effectiveStatus)) return false;
      if (reviewFilter === "failed" && effectiveStatus !== "FAILED") return false;
      if (!normalizedQuery) return true;
      return review.id.toLowerCase().includes(normalizedQuery);
    });
  }, [reviews, jobs, reviewFilter, query]);

  const selectedFindings = useMemo(
    () => (selectedReview ? (findingsByReview[selectedReview.id] ?? []) : []),
    [selectedReview, findingsByReview],
  );

  const severityCounts = useMemo(() => {
    const counts: Record<string, number> = {
      CRITICAL: 0,
      HIGH: 0,
      MEDIUM: 0,
      LOW: 0,
      INFO: 0,
    };
    for (const finding of selectedFindings) {
      const key = finding.severity.toUpperCase();
      counts[key] = (counts[key] ?? 0) + 1;
    }
    return counts;
  }, [selectedFindings]);

  const filteredFindings = useMemo(() => {
    if (severityFilter === "ALL") return selectedFindings;
    return selectedFindings.filter(
      (finding) => finding.severity.toUpperCase() === severityFilter,
    );
  }, [selectedFindings, severityFilter]);

  const safeFindingIndex =
    filteredFindings.length === 0
      ? 0
      : Math.min(activeFindingIndex, filteredFindings.length - 1);
  const focusedFinding = filteredFindings[safeFindingIndex] ?? null;

  const selectReview = (reviewId: string) => {
    setSelectedId(reviewId);
    setSeverityFilter("ALL");
    setActiveFindingIndex(0);
  };

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Reviews & Findings</h1>
          <p className="text-sm text-muted-foreground">
            Monitor asynchronous review jobs and inspect diff-anchored AI findings.
          </p>
        </div>
        <Button
          variant="outline"
          size="sm"
          onClick={() => void loadReviews()}
          disabled={loading}
        >
          <RefreshCw className={`mr-1.5 h-3.5 w-3.5 ${loading ? "animate-spin" : ""}`} />
          Refresh
        </Button>
      </div>

      {error && <ErrorBanner message={error} onRetry={() => void loadReviews()} />}

      {loading ? (
        <div className="grid gap-5 lg:grid-cols-12">
          <div className="space-y-2.5 lg:col-span-4">
            <Skeleton className="h-9 w-full" />
            <Skeleton className="h-20 w-full" />
            <Skeleton className="h-20 w-full" />
            <Skeleton className="h-20 w-full" />
          </div>
          <Skeleton className="h-[460px] w-full lg:col-span-8" />
        </div>
      ) : reviews.length === 0 ? (
        <EmptyState
          icon={<History className="h-5 w-5" />}
          title="No reviews yet"
          description="Synchronize a repository and trigger a review on an open pull request to see findings here."
          action={
            <Link href="/dashboard/repositories">
              <Button size="sm">
                <GitBranch className="mr-1.5 h-3.5 w-3.5" />
                Go to Repositories
              </Button>
            </Link>
          }
        />
      ) : (
        <div className="grid items-start gap-5 lg:grid-cols-12">
          <div
            className={`space-y-3 lg:col-span-4 ${
              selectedReview ? "hidden lg:block" : "block"
            }`}
          >
            <div className="relative">
              <Search className="pointer-events-none absolute top-2.5 left-2.5 h-4 w-4 text-muted-foreground" />
              <Input
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="Filter by review ID..."
                className="pl-8"
                aria-label="Filter reviews by ID"
              />
            </div>

            <div className="flex items-center gap-1 rounded-md border bg-card p-1">
              {(["all", "active", "completed", "failed"] as const).map((filter) => (
                <button
                  key={filter}
                  type="button"
                  onClick={() => setReviewFilter(filter)}
                  className={`flex-1 rounded px-2 py-1 text-xs font-medium capitalize transition-colors ${
                    reviewFilter === filter
                      ? "bg-primary text-primary-foreground"
                      : "text-muted-foreground hover:text-foreground"
                  }`}
                >
                  {filter}
                </button>
              ))}
            </div>

            <div className="divide-y overflow-hidden rounded-lg border bg-card">
              {filteredReviews.length === 0 ? (
                <p className="p-6 text-center text-sm text-muted-foreground">
                  No reviews match your filter.
                </p>
              ) : (
                filteredReviews.map((review) => {
                  const status = jobs[review.id]?.status ?? review.status;
                  const isSelected = selectedReview?.id === review.id;
                  const isActive = activeStatuses.has(status);
                  const isFailed = status === "FAILED";
                  return (
                    <button
                      type="button"
                      key={review.id}
                      onClick={() => selectReview(review.id)}
                      className={`flex w-full items-center gap-3 p-3.5 text-left transition-colors hover:bg-muted/40 ${
                        isSelected ? "bg-muted/60" : ""
                      }`}
                    >
                      <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-md border bg-background">
                        {isActive ? (
                          <CircleDashed className="h-4 w-4 animate-spin text-sky-600 dark:text-sky-400" />
                        ) : isFailed ? (
                          <XCircle className="h-4 w-4 text-destructive" />
                        ) : (
                          <CheckCircle2 className="h-4 w-4 text-emerald-600 dark:text-emerald-400" />
                        )}
                      </div>
                      <div className="min-w-0 flex-1">
                        <p className="truncate font-mono text-xs font-medium">
                          review/{review.id.slice(0, 8)}
                        </p>
                        <p className="truncate text-xs text-muted-foreground">
                          {formatDate(review.createdAt)}
                        </p>
                      </div>
                      <StatusBadge value={status} />
                    </button>
                  );
                })
              )}
            </div>
          </div>

          <div className={`lg:col-span-8 ${selectedReview ? "block" : "hidden lg:block"}`}>
            {!selectedReview ? (
              <EmptyState
                icon={<FileCode2 className="h-5 w-5" />}
                title="Select a review"
                description="Choose a review run from the list to inspect its status, severity breakdown, and findings."
              />
            ) : (
              <div className="space-y-4">
                <div className="rounded-lg border bg-card p-4 sm:p-5">
                  <div className="flex flex-wrap items-start justify-between gap-3">
                    <div className="space-y-1">
                      <button
                        type="button"
                        onClick={() => setSelectedId(null)}
                        className="mb-1 inline-flex items-center gap-1 text-xs font-medium text-muted-foreground hover:text-foreground lg:hidden"
                      >
                        <ArrowLeft className="h-3.5 w-3.5" />
                        Back to Reviews
                      </button>
                      <div className="flex flex-wrap items-center gap-2.5">
                        <h2 className="font-mono text-base font-semibold">
                          review/{selectedReview.id.slice(0, 8)}
                        </h2>
                        {selectedStatus && <StatusBadge value={selectedStatus} />}
                      </div>
                      <p className="text-xs text-muted-foreground">
                        Created {formatDate(selectedReview.createdAt)}
                        {selectedReview.completedAt
                          ? ` · Completed ${formatDate(selectedReview.completedAt)}`
                          : ""}
                      </p>
                    </div>

                    {selectedStatus && terminalWithFindings.has(selectedStatus) && (
                      <Button
                        size="sm"
                        variant="outline"
                        disabled={loadingFindingsId === selectedReview.id}
                        onClick={() => void loadFindings(selectedReview.id)}
                      >
                        <RefreshCw
                          className={`mr-1.5 h-3.5 w-3.5 ${
                            loadingFindingsId === selectedReview.id ? "animate-spin" : ""
                          }`}
                        />
                        {loadingFindingsId === selectedReview.id ? "Loading..." : "Reload Findings"}
                      </Button>
                    )}
                  </div>

                  {jobs[selectedReview.id] && (
                    <div className="mt-3 rounded-md border bg-muted/30 px-3 py-2 font-mono text-xs text-muted-foreground">
                      Attempt {jobs[selectedReview.id].attemptCount}
                      {jobs[selectedReview.id].errorMessage
                        ? ` · ${jobs[selectedReview.id].errorMessage}`
                        : ""}
                    </div>
                  )}

                  {selectedStatus === "PARTIAL" && (
                    <div className="mt-3 rounded-md border border-amber-500/30 bg-amber-500/5 px-3 py-2 text-xs text-amber-800 dark:text-amber-300">
                      Partial review: one or more files in this pull request were skipped because they were binary, deleted, incomplete, or exceeded configured size limits.
                    </div>
                  )}

                  {selectedStatus && terminalWithFindings.has(selectedStatus) && (
                    <div className="mt-4 flex flex-wrap items-center gap-1.5 border-t pt-4">
                      <button
                        type="button"
                        onClick={() => {
                          setSeverityFilter("ALL");
                          setActiveFindingIndex(0);
                        }}
                        className={`rounded-md border px-2.5 py-1 font-mono text-xs transition-colors ${
                          severityFilter === "ALL"
                            ? "border-primary bg-primary text-primary-foreground"
                            : "bg-background text-muted-foreground hover:text-foreground"
                        }`}
                      >
                        ALL ({selectedFindings.length})
                      </button>
                      {severityOrder.map((severity) => (
                        <button
                          key={severity}
                          type="button"
                          onClick={() => {
                            setSeverityFilter(severity);
                            setActiveFindingIndex(0);
                          }}
                          className={`rounded-md border px-2.5 py-1 font-mono text-xs transition-colors ${
                            severityFilter === severity
                              ? "border-primary bg-primary text-primary-foreground"
                              : "bg-background text-muted-foreground hover:text-foreground"
                          }`}
                        >
                          {severity} ({severityCounts[severity] ?? 0})
                        </button>
                      ))}
                    </div>
                  )}
                </div>

                {selectedStatus && activeStatuses.has(selectedStatus) ? (
                  <div className="flex items-center gap-3 rounded-lg border bg-card p-6 text-sm text-muted-foreground">
                    <CircleDashed className="h-4 w-4 animate-spin text-sky-600 dark:text-sky-400" />
                    <span>
                      Review job is currently {selectedStatus.toLowerCase()}. Status updates automatically every 5 seconds.
                    </span>
                  </div>
                ) : selectedStatus === "FAILED" ? (
                  <ErrorBanner message={formatFailureBanner(jobs[selectedReview.id])} />
                ) : loadingFindingsId === selectedReview.id && !findingsByReview[selectedReview.id] ? (
                  <div className="space-y-3">
                    <Skeleton className="h-24 w-full" />
                    <Skeleton className="h-56 w-full" />
                  </div>
                ) : filteredFindings.length === 0 ? (
                  <EmptyState
                    icon={<CheckCircle2 className="h-5 w-5 text-emerald-600 dark:text-emerald-400" />}
                    title={
                      selectedFindings.length === 0
                        ? "No issues detected"
                        : `No ${severityFilter} findings`
                    }
                    description={
                      selectedFindings.length === 0
                        ? "Gemini found no actionable issues in the reviewed diff hunks."
                        : "Select ALL above to inspect findings of other severities."
                    }
                  />
                ) : (
                  <div className="space-y-4">
                    {focusedFinding && (
                      <article className="overflow-hidden rounded-lg border bg-card">
                        <div className="flex flex-wrap items-center justify-between gap-2 border-b bg-muted/30 px-4 py-3">
                          <div className="flex min-w-0 items-center gap-2">
                            <FileCode2 className="h-4 w-4 shrink-0 text-muted-foreground" />
                            <span className="truncate font-mono text-xs font-medium">
                              {focusedFinding.filePath}:{focusedFinding.lineNumber ?? "—"}
                            </span>
                          </div>
                          <div className="flex items-center gap-1.5">
                            <span className="font-mono text-xs text-muted-foreground">
                              {safeFindingIndex + 1} / {filteredFindings.length}
                            </span>
                            <Button
                              size="icon"
                              variant="outline"
                              className="h-7 w-7"
                              disabled={safeFindingIndex === 0}
                              onClick={() => setActiveFindingIndex((i) => Math.max(0, i - 1))}
                              aria-label="Previous finding"
                            >
                              <ChevronLeft className="h-3.5 w-3.5" />
                            </Button>
                            <Button
                              size="icon"
                              variant="outline"
                              className="h-7 w-7"
                              disabled={safeFindingIndex >= filteredFindings.length - 1}
                              onClick={() =>
                                setActiveFindingIndex((i) =>
                                  Math.min(filteredFindings.length - 1, i + 1),
                                )
                              }
                              aria-label="Next finding"
                            >
                              <ChevronRight className="h-3.5 w-3.5" />
                            </Button>
                          </div>
                        </div>

                        <div className="space-y-4 p-4 sm:p-5">
                          <div className="flex flex-wrap items-center gap-2">
                            <StatusBadge value={focusedFinding.severity} kind="severity" />
                            {focusedFinding.category && (
                              <Badge variant="outline" className="font-mono text-[11px]">
                                {focusedFinding.category}
                              </Badge>
                            )}
                            {focusedFinding.confidence && (
                              <span className="ml-auto font-mono text-xs text-muted-foreground">
                                Confidence: {focusedFinding.confidence}
                              </span>
                            )}
                          </div>

                          <div>
                            <h3 className="text-base font-semibold tracking-tight">
                              {focusedFinding.title || focusedFinding.message}
                            </h3>
                            <p className="mt-1.5 text-sm leading-relaxed text-muted-foreground">
                              {focusedFinding.description || focusedFinding.message}
                            </p>
                          </div>

                          {focusedFinding.evidence && (
                            <div className="rounded-md border bg-muted/40 p-3">
                              <p className="mb-1.5 font-mono text-[11px] font-medium text-muted-foreground uppercase">
                                Evidence
                              </p>
                              <pre className="overflow-x-auto font-mono text-xs leading-relaxed">
                                <code>{focusedFinding.evidence}</code>
                              </pre>
                            </div>
                          )}

                          {focusedFinding.impact && (
                            <div className="rounded-md border bg-background p-3">
                              <p className="mb-1 font-mono text-[11px] font-medium text-muted-foreground uppercase">
                                Impact
                              </p>
                              <p className="text-sm leading-relaxed">{focusedFinding.impact}</p>
                            </div>
                          )}

                          {focusedFinding.suggestedFix && (
                            <div className="rounded-md border bg-muted/40 p-3">
                              <p className="mb-1.5 font-mono text-[11px] font-medium text-muted-foreground uppercase">
                                Suggested Fix
                              </p>
                              <pre className="overflow-x-auto font-mono text-xs leading-relaxed">
                                <code>{focusedFinding.suggestedFix}</code>
                              </pre>
                            </div>
                          )}
                        </div>
                      </article>
                    )}

                    <div className="divide-y overflow-hidden rounded-lg border bg-card">
                      {filteredFindings.map((finding, index) => {
                        const isFocused = index === safeFindingIndex;
                        return (
                          <button
                            type="button"
                            key={finding.id}
                            onClick={() => setActiveFindingIndex(index)}
                            className={`flex w-full items-center justify-between gap-3 px-4 py-3 text-left transition-colors hover:bg-muted/40 ${
                              isFocused ? "bg-muted/60" : ""
                            }`}
                          >
                            <div className="min-w-0 flex-1 space-y-0.5">
                              <p className="truncate text-sm font-medium">
                                {finding.title || finding.message}
                              </p>
                              <p className="truncate font-mono text-xs text-muted-foreground">
                                {finding.filePath}:{finding.lineNumber ?? "—"}
                              </p>
                            </div>
                            <StatusBadge value={finding.severity} kind="severity" />
                          </button>
                        );
                      })}
                    </div>
                  </div>
                )}
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
