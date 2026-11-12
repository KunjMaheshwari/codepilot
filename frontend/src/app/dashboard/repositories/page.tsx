"use client";

import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { api, apiErrorMessage, formatDate, type PullRequest, type Repository } from "@/lib/api";

export default function RepositoriesPage() {
  const [repositories, setRepositories] = useState<Repository[]>([]);
  const [selected, setSelected] = useState<Repository | null>(null);
  const [pullRequests, setPullRequests] = useState<PullRequest[]>([]);
  const [loading, setLoading] = useState(true);
  const [message, setMessage] = useState("");

  useEffect(() => {
    let cancelled = false;
    api.getRepositories()
      .then((page) => {
        if (!cancelled) setRepositories(page.content);
      })
      .catch((error: unknown) => {
        if (!cancelled) setMessage(apiErrorMessage(error));
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
    setMessage("");
    api.getPullRequests(repository.id)
      .then((page) => setPullRequests(page.content))
      .catch((error: unknown) => setMessage(apiErrorMessage(error)));
  };

  const sync = () => {
    setMessage("");
    api.syncRepositories()
      .then((page) => setRepositories(page.content))
      .catch((error: unknown) => setMessage(apiErrorMessage(error)));
  };

  return (
    <div className="flex flex-col gap-4 pt-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-3xl font-bold tracking-tight">Repositories</h1>
        <Button onClick={sync}>Synchronize GitHub</Button>
      </div>
      {message && <p className="rounded-md border border-destructive/40 p-3 text-sm text-destructive">{message}</p>}
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
            className="rounded-lg border bg-background p-4 text-left transition hover:border-primary"
            onClick={() => openRepository(repository)}
          >
            <div className="flex items-start justify-between gap-3">
              <div>
                <h2 className="font-semibold">{repository.fullName}</h2>
                <p className="mt-1 text-sm text-muted-foreground">{repository.description || "No description"}</p>
              </div>
              <Badge variant="outline">{repository.isPrivate ? "Private" : "Public"}</Badge>
            </div>
            <p className="mt-4 text-xs text-muted-foreground">
              Default: {repository.defaultBranch || "—"} · Synced: {formatDate(repository.lastSyncedAt)}
            </p>
          </button>
        ))}
      </div>
      {selected && (
        <section className="rounded-lg border bg-background p-4">
          <h2 className="text-xl font-semibold">{selected.fullName} pull requests</h2>
          {pullRequests.length === 0 ? (
            <p className="mt-3 text-sm text-muted-foreground">No synchronized pull requests found.</p>
          ) : (
            <div className="mt-4 space-y-3">
              {pullRequests.map((pullRequest) => (
                <div key={pullRequest.id} className="flex flex-wrap items-center justify-between gap-3 rounded-md border p-3">
                  <div>
                    <p className="font-medium">#{pullRequest.prNumber} {pullRequest.title}</p>
                    <p className="text-sm text-muted-foreground">
                      {pullRequest.headBranch || "—"} → {pullRequest.baseBranch || "—"} · {pullRequest.author}
                    </p>
                  </div>
                  <div className="flex items-center gap-2">
                    <Badge variant={pullRequest.status === "open" ? "default" : "secondary"}>{pullRequest.status}</Badge>
                    <Button
                      size="sm"
                      onClick={() => api.createReview(selected.id, pullRequest.id)
                        .then(() => setMessage("Review queued. Open Reviews to monitor progress."))
                        .catch((error: unknown) => setMessage(apiErrorMessage(error)))}
                    >
                      Review
                    </Button>
                  </div>
                </div>
              ))}
            </div>
          )}
        </section>
      )}
    </div>
  );
}
