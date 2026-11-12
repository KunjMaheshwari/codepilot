"use client";

import Link from "next/link";
import { ArrowRight, CheckCircle2, CircleDashed, XCircle } from "lucide-react";
import { StatusBadge } from "@/components/ui/status-badge";
import { formatDate, type Review } from "@/lib/api";

const activeStatuses = new Set(["PENDING", "QUEUED", "PROCESSING", "RETRYING"]);

export function RecentReviews({ reviews }: { reviews: Review[] }) {
  return (
    <div className="flex h-full flex-col rounded-lg border bg-card p-5 text-card-foreground">
      <div className="mb-4 flex items-center justify-between gap-2">
        <div>
          <h2 className="text-sm font-semibold tracking-tight">Recent Reviews</h2>
          <p className="text-xs text-muted-foreground">Latest pull request review runs</p>
        </div>
        <Link
          href="/dashboard/reviews"
          className="inline-flex items-center gap-1 text-xs font-medium text-muted-foreground hover:text-foreground"
        >
          All reviews
          <ArrowRight className="h-3 w-3" />
        </Link>
      </div>

      {reviews.length === 0 ? (
        <div className="flex flex-1 flex-col items-center justify-center rounded-md border border-dashed py-12 text-center">
          <p className="text-sm font-medium">No reviews yet</p>
          <p className="mt-1 text-xs text-muted-foreground">
            Open Repositories to trigger a manual pull request review.
          </p>
        </div>
      ) : (
        <div className="divide-y">
          {reviews.slice(0, 6).map((review) => {
            const isActive = activeStatuses.has(review.status);
            const isFailed = review.status === "FAILED";
            return (
              <Link
                key={review.id}
                href={`/dashboard/reviews?reviewId=${encodeURIComponent(review.id)}`}
                className="flex items-center gap-3 py-3 transition-colors first:pt-0 last:pb-0 hover:bg-muted/30"
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
                <StatusBadge value={review.status} />
              </Link>
            );
          })}
        </div>
      )}
    </div>
  );
}
