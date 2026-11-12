"use client";

import Link from "next/link";
import { useCallback, useEffect, useMemo, useState } from "react";
import { ExternalLink, GitBranch, GitPullRequest, RefreshCw, Search } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { EmptyState, ErrorBanner } from "@/components/ui/empty-state";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { StatusBadge } from "@/components/ui/status-badge";
import { useToast } from "@/components/ui/toast";
import {
  api,
  apiErrorMessage,
  formatDate,
  type PullRequest,
  type Repository,
  type Review,
} from "@/lib/api";

type RepoFilter = "all" | "active" | "inactive";
type PrFilter = "all" | "open" | "closed" | "merged";

export default function RepositoriesPage() {
  const { toast } = useToast();
  const [repositories, setRepositories] = useState<Repository[]>([]);
  const [selected, setSelected] = useState<Repository | null>(null);
  const [pullRequests, setPullRequests] = useState<PullRequest[]>([]);
  const [queuedReviews, setQueuedReviews] = useState<Record<string, Review>>({});
  const [loading, setLoading] = useState(true);
  const [syncingRepos, setSyncingRepos] = useState(false);
  const [loadingPrs, setLoadingPrs] = useState(false);
  const [syncingPrs, setSyncingPrs] = useState(false);
  const [submittingPrId, setSubmittingPrId] = useState<string | null>(null);
  const [errorMessage, setErrorMessage] = useState("");
  const [repoQuery, setRepoQuery] = useState("");
  const [repoFilter, setRepoFilter] = useState<RepoFilter>("all");
  const [prFilter, setPrFilter] = useState<PrFilter>("all");

  const loadRepositories = useCallback(async () => {
    setLoading(true);
    setErrorMessage("");
    try {
      const page = await api.getRepositories(0, 100);
      setRepositories(page.content);
    } catch (error: unknown) {
      setErrorMessage(apiErrorMessage(error));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    api
      .getRepositories(0, 100)
      .then((page) => {
        if (!cancelled) setRepositories(page.content);
      })
      .catch((error: unknown) => {
        if (!cancelled) setErrorMessage(apiErrorMessage(error));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const openRepository = (repository: Repository) => {
    setSelected(repository);
    setErrorMessage("");
    setPrFilter("all");
    setLoadingPrs(true);
    api
      .getPullRequests(repository.id, 0, 100)
      .then((page) => setPullRequests(page.content))
      .catch((error: unknown) => setErrorMessage(apiErrorMessage(error)))
      .finally(() => setLoadingPrs(false));
  };

  const syncRepositories = () => {
    if (syncingRepos) return;
    setErrorMessage("");
    setSyncingRepos(true);
    api
      .syncRepositories()
      .then((page) => {
        setRepositories(page.content);
        if (selected) {
          const updatedSelected = page.content.find((item) => item.id === selected.id) ?? null;
          setSelected(updatedSelected);
        }
        toast({
          title: "Repositories synchronized",
          description: `Loaded ${page.content.length} repositories from GitHub.`,
          variant: "success",
        });
      })
      .catch((error: unknown) => {
        const msg = apiErrorMessage(error);
        setErrorMessage(msg);
        toast({ title: "Repository sync failed", description: msg, variant: "error" });
      })
      .finally(() => setSyncingRepos(false));
  };

  const syncPullRequests = () => {
    if (!selected || syncingPrs) return;
    setErrorMessage("");
    setSyncingPrs(true);
    api
      .syncPullRequests(selected.id, 0, 100)
      .then((page) => {
        setPullRequests(page.content);
        toast({
          title: "Pull requests synchronized",
          description: `Updated ${page.content.length} pull requests for ${selected.fullName}.`,
          variant: "success",
        });
      })
      .catch((error: unknown) => {
        const msg = apiErrorMessage(error);
        setErrorMessage(msg);
        toast({ title: "Pull request sync failed", description: msg, variant: "error" });
      })
      .finally(() => setSyncingPrs(false));
  };

  const triggerReview = (pullRequest: PullRequest) => {
    if (!selected || submittingPrId) return;
    setErrorMessage("");
    setSubmittingPrId(pullRequest.id);
    api
      .createReview(selected.id, pullRequest.id)
      .then((review) => {
        setQueuedReviews((current) => ({ ...current, [pullRequest.id]: review }));
        toast({
          title: `Review queued for PR #${pullRequest.prNumber}`,
          description: `Review ${review.id.slice(0, 8)} (${review.status}).`,
          variant: "success",
        });
      })
      .catch((error: unknown) => {
        const msg = apiErrorMessage(error);
        setErrorMessage(msg);
        toast({ title: "Could not queue review", description: msg, variant: "error" });
      })
      .finally(() => setSubmittingPrId(null));
  };

  const filteredRepositories = useMemo(() => {
    const query = repoQuery.trim().toLowerCase();
    return repositories.filter((repo) => {
      if (repoFilter === "active" && !repo.isActive) return false;
      if (repoFilter === "inactive" && repo.isActive) return false;
      if (!query) return true;
      return (
        repo.fullName.toLowerCase().includes(query) ||
        (repo.description ?? "").toLowerCase().includes(query)
      );
    });
  }, [repositories, repoQuery, repoFilter]);

  const filteredPullRequests = useMemo(() => {
    if (prFilter === "all") return pullRequests;
    return pullRequests.filter((pr) => pr.status.toLowerCase() === prFilter);
  }, [pullRequests, prFilter]);

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Repositories</h1>
          <p className="text-sm text-muted-foreground">
            Synchronize GitHub repositories, inspect pull requests, and trigger manual AI reviews.
          </p>
        </div>
        <Button onClick={syncRepositories} disabled={syncingRepos} size="sm">
          <RefreshCw className={`mr-1.5 h-3.5 w-3.5 ${syncingRepos ? "animate-spin" : ""}`} />
          {syncingRepos ? "Synchronizing..." : "Synchronize GitHub"}
        </Button>
      </div>

      {errorMessage && (
        <ErrorBanner message={errorMessage} onRetry={() => void loadRepositories()} />
      )}

      {loading ? (
        <div className="grid gap-5 lg:grid-cols-12">
          <div className="space-y-2.5 lg:col-span-5">
            <Skeleton className="h-9 w-full" />
            <Skeleton className="h-24 w-full" />
            <Skeleton className="h-24 w-full" />
            <Skeleton className="h-24 w-full" />
          </div>
          <Skeleton className="h-96 w-full lg:col-span-7" />
        </div>
      ) : repositories.length === 0 ? (
        <EmptyState
          icon={<GitBranch className="h-5 w-5" />}
          title="No repositories connected"
          description="Synchronize your GitHub account to import repositories available for pull request review."
          action={
            <Button onClick={syncRepositories} disabled={syncingRepos} size="sm">
              <RefreshCw className={`mr-1.5 h-3.5 w-3.5 ${syncingRepos ? "animate-spin" : ""}`} />
              Synchronize GitHub
            </Button>
          }
        />
      ) : (
        <div className="grid items-start gap-5 lg:grid-cols-12">
          <div className="space-y-3 lg:col-span-5">
            <div className="flex flex-col gap-2 sm:flex-row">
              <div className="relative flex-1">
                <Search className="pointer-events-none absolute top-2.5 left-2.5 h-4 w-4 text-muted-foreground" />
                <Input
                  value={repoQuery}
                  onChange={(event) => setRepoQuery(event.target.value)}
                  placeholder="Search repositories..."
                  className="pl-8"
                  aria-label="Search repositories"
                />
              </div>
              <div className="flex items-center gap-1 rounded-md border bg-card p-1">
                {(["all", "active", "inactive"] as const).map((filter) => (
                  <button
                    key={filter}
                    type="button"
                    onClick={() => setRepoFilter(filter)}
                    className={`rounded px-2.5 py-1 text-xs font-medium capitalize transition-colors ${
                      repoFilter === filter
                        ? "bg-primary text-primary-foreground"
                        : "text-muted-foreground hover:text-foreground"
                    }`}
                  >
                    {filter}
                  </button>
                ))}
              </div>
            </div>

            <div className="divide-y overflow-hidden rounded-lg border bg-card">
              {filteredRepositories.length === 0 ? (
                <p className="p-6 text-center text-sm text-muted-foreground">
                  No repositories match your current filter.
                </p>
              ) : (
                filteredRepositories.map((repository) => {
                  const isSelected = selected?.id === repository.id;
                  return (
                    <button
                      type="button"
                      key={repository.id}
                      onClick={() => openRepository(repository)}
                      className={`flex w-full flex-col gap-1.5 p-4 text-left transition-colors hover:bg-muted/40 ${
                        isSelected ? "bg-muted/60" : ""
                      }`}
                    >
                      <div className="flex items-start justify-between gap-2">
                        <span className="truncate font-mono text-sm font-medium text-foreground">
                          {repository.fullName}
                        </span>
                        <div className="flex shrink-0 items-center gap-1.5">
                          {!repository.isActive && <Badge variant="secondary">Inactive</Badge>}
                          <Badge variant="outline">
                            {repository.isPrivate ? "Private" : "Public"}
                          </Badge>
                        </div>
                      </div>
                      {repository.description && (
                        <p className="line-clamp-2 text-xs text-muted-foreground">
                          {repository.description}
                        </p>
                      )}
                      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 pt-1 font-mono text-[11px] text-muted-foreground">
                        <span>branch: {repository.defaultBranch || "—"}</span>
                        <span>synced: {formatDate(repository.lastSyncedAt)}</span>
                      </div>
                    </button>
                  );
                })
              )}
            </div>
          </div>

          <div className="lg:col-span-7">
            {!selected ? (
              <EmptyState
                icon={<GitPullRequest className="h-5 w-5" />}
                title="Select a repository"
                description="Choose a repository from the list to inspect its pull requests and trigger manual reviews."
              />
            ) : (
              <section className="overflow-hidden rounded-lg border bg-card">
                <div className="flex flex-wrap items-start justify-between gap-3 border-b p-4 sm:p-5">
                  <div className="min-w-0 space-y-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <h2 className="truncate font-mono text-base font-semibold">
                        {selected.fullName}
                      </h2>
                      {selected.htmlUrl && (
                        <a
                          href={selected.htmlUrl}
                          target="_blank"
                          rel="noreferrer"
                          className="inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"
                        >
                          GitHub
                          <ExternalLink className="h-3 w-3" />
                        </a>
                      )}
                    </div>
                    <p className="text-xs text-muted-foreground">
                      {selected.description || "No repository description"}
                    </p>
                  </div>
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={syncPullRequests}
                    disabled={syncingPrs}
                  >
                    <RefreshCw className={`mr-1.5 h-3.5 w-3.5 ${syncingPrs ? "animate-spin" : ""}`} />
                    {syncingPrs ? "Syncing PRs..." : "Sync Pull Requests"}
                  </Button>
                </div>

                <div className="flex flex-wrap items-center justify-between gap-2 border-b bg-muted/25 px-4 py-2.5 sm:px-5">
                  <span className="text-xs font-medium text-muted-foreground">
                    {filteredPullRequests.length} pull request
                    {filteredPullRequests.length === 1 ? "" : "s"}
                  </span>
                  <div className="flex items-center gap-1">
                    {(["all", "open", "closed", "merged"] as const).map((status) => (
                      <button
                        key={status}
                        type="button"
                        onClick={() => setPrFilter(status)}
                        className={`rounded px-2 py-0.5 text-xs font-medium capitalize transition-colors ${
                          prFilter === status
                            ? "bg-primary text-primary-foreground"
                            : "text-muted-foreground hover:text-foreground"
                        }`}
                      >
                        {status}
                      </button>
                    ))}
                  </div>
                </div>

                {loadingPrs ? (
                  <div className="space-y-3 p-4 sm:p-5">
                    <Skeleton className="h-16 w-full" />
                    <Skeleton className="h-16 w-full" />
                    <Skeleton className="h-16 w-full" />
                  </div>
                ) : filteredPullRequests.length === 0 ? (
                  <div className="p-8 text-center">
                    <p className="text-sm font-medium">No pull requests found</p>
                    <p className="mt-1 text-xs text-muted-foreground">
                      {pullRequests.length === 0
                        ? "Click Sync Pull Requests to import open, closed, and merged PRs from GitHub."
                        : "Try changing the pull request status filter."}
                    </p>
                  </div>
                ) : (
                  <div className="divide-y">
                    {filteredPullRequests.map((pullRequest) => {
                      const isOpen = pullRequest.status.toLowerCase() === "open";
                      const isSubmitting = submittingPrId === pullRequest.id;
                      const queuedReview = queuedReviews[pullRequest.id];

                      return (
                        <div
                          key={pullRequest.id}
                          className="flex flex-col gap-3 p-4 sm:flex-row sm:items-center sm:justify-between sm:px-5"
                        >
                          <div className="min-w-0 space-y-1">
                            <div className="flex flex-wrap items-center gap-2">
                              <span className="font-mono text-xs text-muted-foreground">
                                #{pullRequest.prNumber}
                              </span>
                              <p className="truncate text-sm font-medium">{pullRequest.title}</p>
                            </div>
                            <div className="flex flex-wrap items-center gap-x-2.5 gap-y-1 text-xs text-muted-foreground">
                              <span>{pullRequest.author}</span>
                              <span>·</span>
                              <span className="font-mono text-[11px]">
                                {pullRequest.headBranch || "—"} → {pullRequest.baseBranch || "—"}
                              </span>
                              {pullRequest.headSha && (
                                <>
                                  <span>·</span>
                                  <span className="font-mono text-[11px]">
                                    {pullRequest.headSha.slice(0, 7)}
                                  </span>
                                </>
                              )}
                            </div>
                          </div>

                          <div className="flex shrink-0 items-center gap-2">
                            <StatusBadge value={pullRequest.status} />
                            {queuedReview ? (
                              <Link href={`/dashboard/reviews?reviewId=${encodeURIComponent(queuedReview.id)}`}>
                                <Button size="sm" variant="outline">
                                  Inspect Review
                                </Button>
                              </Link>
                            ) : (
                              <Button
                                size="sm"
                                disabled={!isOpen || !selected.isActive || isSubmitting}
                                onClick={() => triggerReview(pullRequest)}
                              >
                                {isSubmitting ? "Queueing..." : "Review"}
                              </Button>
                            )}
                          </div>
                        </div>
                      );
                    })}
                  </div>
                )}
              </section>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
