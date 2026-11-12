"use client";

import { useEffect, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { api, apiErrorMessage, formatDate, type Review, type ReviewFinding, type ReviewJob } from "@/lib/api";

const activeStatuses = ["PENDING", "QUEUED", "PROCESSING", "RETRYING"];

export default function ReviewsPage() {
  const [reviews, setReviews] = useState<Review[]>([]);
  const [jobs, setJobs] = useState<Record<string, ReviewJob>>({});
  const [findings, setFindings] = useState<Record<string, ReviewFinding[]>>({});
  const [error, setError] = useState("");

  const load = () => api.getReviews(0, 100)
    .then((page) => setReviews(page.content))
    .catch((reason: unknown) => setError(apiErrorMessage(reason)));

  useEffect(() => { void load(); }, []);

  useEffect(() => {
    const active = reviews.filter((review) => activeStatuses.includes(review.status));
    if (active.length === 0) return;
    const poll = window.setInterval(() => {
      Promise.all(active.map((review) => api.getJob(review.id).then((job) => {
        setJobs((current) => ({ ...current, [review.id]: job }));
        if (!activeStatuses.includes(job.status)) {
          api.getFindings(review.id).then((page) => setFindings((current) => ({ ...current, [review.id]: page.content }))).catch(() => undefined);
          void load();
        }
      }))).catch((reason: unknown) => setError(apiErrorMessage(reason)));
    }, 5000);
    return () => window.clearInterval(poll);
  }, [reviews]);

  return (
    <div className="flex flex-col gap-4 pt-6">
      <h1 className="text-3xl font-bold tracking-tight">Reviews</h1>
      {error && <p className="rounded-md border border-destructive/40 p-3 text-sm text-destructive">{error}</p>}
      {reviews.length === 0 && !error && <p className="rounded-md border border-dashed p-8 text-center text-sm text-muted-foreground">No reviews found.</p>}
      <div className="space-y-4">
        {reviews.map((review) => {
          const job = jobs[review.id];
          const status = job?.status ?? review.status;
          return (
            <article key={review.id} className="rounded-lg border bg-background p-4">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div>
                  <h2 className="font-semibold">Review {review.id.slice(0, 8)}</h2>
                  <p className="text-sm text-muted-foreground">Created {formatDate(review.createdAt)}</p>
                </div>
                <Badge variant={status === "FAILED" ? "destructive" : "secondary"}>{status}</Badge>
              </div>
              {job && <p className="mt-2 text-sm text-muted-foreground">Attempt {job.attemptCount} · {job.errorMessage || "No error"}</p>}
              {findings[review.id] && (
                <div className="mt-4 space-y-3">
                  {findings[review.id].length === 0 && <p className="text-sm text-muted-foreground">No findings.</p>}
                  {findings[review.id].map((finding) => (
                    <div key={finding.id} className="rounded-md border p-3">
                      <div className="flex flex-wrap items-center gap-2">
                        <Badge variant={finding.severity === "CRITICAL" || finding.severity === "HIGH" ? "destructive" : "outline"}>{finding.severity}</Badge>
                        <span className="font-medium">{finding.title || finding.message}</span>
                      </div>
                      <p className="mt-2 text-sm">{finding.description || finding.message}</p>
                      <p className="mt-2 text-xs text-muted-foreground">{finding.filePath}:{finding.lineNumber ?? "—"} · {finding.category || "Uncategorized"}</p>
                      {finding.evidence && <p className="mt-2 text-sm"><strong>Evidence:</strong> {finding.evidence}</p>}
                      {finding.impact && <p className="mt-2 text-sm"><strong>Impact:</strong> {finding.impact}</p>}
                      {finding.suggestedFix && <p className="mt-2 text-sm"><strong>Suggested fix:</strong> {finding.suggestedFix}</p>}
                      {finding.confidence && <p className="mt-2 text-xs text-muted-foreground">Confidence: {finding.confidence}</p>}
                    </div>
                  ))}
                </div>
              )}
            </article>
          );
        })}
      </div>
    </div>
  );
}
