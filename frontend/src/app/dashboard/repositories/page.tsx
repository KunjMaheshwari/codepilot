"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { api, apiErrorMessage, formatDate, type PullRequest, type Repository } from "@/lib/api";

export default function RepositoriesPage() {
  const [repositories, setRepositories] = useState<Repository[]>([]);
  const [selected, setSelected] = useState<Repository | null>(null);
  const [pullRequests, setPullRequests] = useState<PullRequest[]>([]);
  const [loading, setLoading] = useState(true);
  const [syncingRepos, setSyncingRepos] = useState(false);
  const [loadingPrs, setLoadingPrs] = useState(false);
  const [syncingPrs, setSyncingPrs] = useState(false);
  const [submittingPrId, setSubmittingPrId] = useState<string | null>(null);
  const [errorMessage, setErrorMessage] = useState("");
  const [statusMessage, setStatusMessage] = useState("");

  useEffect(() => {
    let cancelled = false;
    api.getRepositories()
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
    setStatusMessage("");
    setLoadingPrs(true);
    api.getPullRequests(repository.id)
      .then((page) => setPullRequests(page.content))
      .catch((error: unknown) => setErrorMessage(apiErrorMessage(error)))
      .finally(() => setLoadingPrs(false));
  };

  const sync = () => {
    if (syncingRepos) return;
    setErrorMessage("");
    setStatusMessage("");
    setSyncingRepos(true);
    api.syncRepositories()
      .then((page) => {
        setRepositories(page.content);
        setStatusMessage(`Synchronized ${page.content.length} repositories from GitHub.`);
      })
      .catch((error: unknown) => setErrorMessage(apiErrorMessage(error)))
      .finally(() => setSyncingRepos(false));
  };

  const syncPullRequests = () => {
    if (!selected || syncingPrs) return;
    setErrorMessage("");
    setStatusMessage("");
    setSyncingPrs(true);
    api.syncPullRequests(selected.id)
      .then((page) => {
        setPullRequests(page.content);
        setStatusMessage(`Synchronized pull requests for ${selected.fullName}.`);
      })
      .catch((error: unknown) => setErrorMessage(apiErrorMessage(error)))
      .finally(() => setSyncingPrs(false));
  };

  const triggerReview = (pullRequest: PullRequest) => {
    if (!selected || submittingPrId) return;
    setErrorMessage("");
    setStatusMessage("");
    setSubmittingPrId(pullRequest.id);
    api.createReview(selected.id, pullRequest.id)
      .then((review) => {
        setStatusMessage(
          `Review ${review.id.slice(0, 8)} (${review.status}) queued for PR #${pullRequest.prNumber}.`,
        );
      })
      .catch((error: unknown) => setErrorMessage(apiErrorMessage(error)))
      .finally(() => setSubmittingPrId(null));
  };

  return (
    <div className="flex flex-col gap-4 pt-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-3xl font-bold tracking-tight">Repositories</h1>
        <Button onClick={sync} disabled={syncingRepos}>
          {syncingRepos ? "Synchronizing..." : "Synchronize GitHub"}
        </Button>
      </div>
      {errorMessage && (
        <p className="rounded-md border border-destructive/40 p-3 text-sm text-destructive">{errorMessage}</p>
      )}
      {statusMessage && (
        <div className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-primary/40 bg-primary/5 p-3 text-sm">
          <span>{statusMessage}</span>
          <Link href="/dashboard/reviews" className="font-medium underline">
            Open Reviews
          </Link>
        </div>
      )}
      {loading && <p className="text-sm text-muted-foreground">Loading repositories...</p>}
      {!loading && repositories.length === 0 && (
        <div className="rounded-md border border-dashed p-8 text-center">
          <h2 className="text-xl font-semibold">No repositories connected</h2>
          <p className="mt-2 text-sm text-muted-foreground">Synchronize GitHub to import repositories you can review.</p>
        </div>
      )}
      <div className="grid gap-4 md:grid-cols-2">
        {repositories.map((repository) => (
          <button
            type="button"
            key={repository.id}
            className={`rounded-lg border bg-background p-4 text-left transition hover:border-primary ${
              selected?.id === repository.id ? "border-primary ring-1 ring-primary" : ""
            }`}
            onClick={() => openRepository(repository)}
          >
            <div className="flex items-start justify-between gap-3">
              <div>
                <h2 className="font-semibold">{repository.fullName}</h2>
                <p className="mt-1 text-sm text-muted-foreground">{repository.description || "No description"}</p>
              </div>
              <div className="flex items-center gap-1.5">
                {!repository.isActive && <Badge variant="secondary">Inactive</Badge>}
                <Badge variant="outline">{repository.isPrivate ? "Private" : "Public"}</Badge>
              </div>
            </div>
            <p className="mt-4 text-xs text-muted-foreground">
              Default: {repository.defaultBranch || "—"} · Synced: {formatDate(repository.lastSyncedAt)}
            </p>
          </button>
        ))}
      </div>
      {selected && (
        <section className="rounded-lg border bg-background p-4">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <h2 className="text-xl font-semibold">{selected.fullName} pull requests</h2>
            <Button variant="outline" size="sm" onClick={syncPullRequests} disabled={syncingPrs}>
              {syncingPrs ? "Syncing PRs..." : "Sync Pull Requests"}
            </Button>
          </div>
          {loadingPrs ? (
            <p className="mt-3 text-sm text-muted-foreground">Loading pull requests...</p>
          ) : pullRequests.length === 0 ? (
            <p className="mt-3 text-sm text-muted-foreground">
              No synchronized pull requests found. Click &ldquo;Sync Pull Requests&rdquo; to import from GitHub.
            </p>
          ) : (
            <div className="mt-4 space-y-3">
              {pullRequests.map((pullRequest) => {
                const isOpen = pullRequest.status.toLowerCase() === "open";
                const isSubmitting = submittingPrId === pullRequest.id;
                return (
                  <div key={pullRequest.id} className="flex flex-wrap items-center justify-between gap-3 rounded-md border p-3">
                    <div>
                      <p className="font-medium">#{pullRequest.prNumber} {pullRequest.title}</p>
                      <p className="text-sm text-muted-foreground">
                        {pullRequest.headBranch || "—"} → {pullRequest.baseBranch || "—"} · {pullRequest.author}
                      </p>
                    </div>
                    <div className="flex items-center gap-2">
                      <Badge variant={isOpen ? "default" : "secondary"}>{pullRequest.status}</Badge>
                      <Button
                        size="sm"
                        disabled={!isOpen || !selected.isActive || isSubmitting}
                        onClick={() => triggerReview(pullRequest)}
                      >
                        {isSubmitting ? "Queueing..." : "Review"}
                      </Button>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </section>
      )}
    </div>
  );
}
