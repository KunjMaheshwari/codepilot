"use client";

import { useEffect, useState } from "react";
import { ActivityChart } from "@/components/dashboard/activity-chart";
import { DashboardStats } from "@/components/dashboard/stats";
import { RecentReviews } from "@/components/dashboard/recent-reviews";
import { api, apiErrorMessage, type DashboardStats as Stats, type Review } from "@/lib/api";

export function DashboardClient() {
  const [stats, setStats] = useState<Stats | null>(null);
  const [reviews, setReviews] = useState<Review[]>([]);
  const [error, setError] = useState("");

  useEffect(() => {
    Promise.all([api.getDashboard(), api.getReviews(0, 10)])
      .then(([dashboard, reviewPage]) => {
        setStats(dashboard);
        setReviews(reviewPage.content);
      })
      .catch((reason: unknown) => setError(apiErrorMessage(reason)));
  }, []);

  return (
    <div className="flex-1 space-y-4 pt-6">
      <h2 className="text-3xl font-bold tracking-tight">Dashboard</h2>
      {error && <p className="rounded-md border border-destructive/40 p-3 text-sm text-destructive">{error}</p>}
      {!stats && !error && <p className="text-sm text-muted-foreground">Loading dashboard...</p>}
      {stats && (
        <>
          <DashboardStats stats={stats} />
          <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-7">
            <ActivityChart reviews={reviews} />
            <RecentReviews reviews={reviews} />
          </div>
        </>
      )}
    </div>
  );
}
