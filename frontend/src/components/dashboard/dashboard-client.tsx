"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { ArrowRight, GitBranch, RefreshCw } from "lucide-react";
import { ActivityChart } from "@/components/dashboard/activity-chart";
import { DashboardStats } from "@/components/dashboard/stats";
import { RecentReviews } from "@/components/dashboard/recent-reviews";
import { Button } from "@/components/ui/button";
import { ErrorBanner } from "@/components/ui/empty-state";
import { Skeleton } from "@/components/ui/skeleton";
import { api, apiErrorMessage, type DashboardStats as Stats, type Review } from "@/lib/api";

export function DashboardClient() {
  const [stats, setStats] = useState<Stats | null>(null);
  const [reviews, setReviews] = useState<Review[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState("");

  const loadDashboard = useCallback(async (isRefresh = false) => {
    if (isRefresh) {
      setRefreshing(true);
    } else {
      setLoading(true);
    }
    setError("");
    try {
      const [dashboard, reviewPage] = await Promise.all([
        api.getDashboard(),
        api.getReviews(0, 10),
      ]);
      setStats(dashboard);
      setReviews(reviewPage.content);
    } catch (reason: unknown) {
      setError(apiErrorMessage(reason));
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    Promise.all([api.getDashboard(), api.getReviews(0, 10)])
      .then(([dashboard, reviewPage]) => {
        if (!cancelled) {
          setStats(dashboard);
          setReviews(reviewPage.content);
        }
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

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Overview</h1>
          <p className="text-sm text-muted-foreground">
            Real-time summary of repositories, pull request reviews, and findings.
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Button
            variant="outline"
            size="sm"
            onClick={() => void loadDashboard(true)}
            disabled={refreshing || loading}
          >
            <RefreshCw className={`mr-1.5 h-3.5 w-3.5 ${refreshing ? "animate-spin" : ""}`} />
            Refresh
          </Button>
          <Link href="/dashboard/repositories">
            <Button size="sm">
              <GitBranch className="mr-1.5 h-3.5 w-3.5" />
              Repositories
              <ArrowRight className="ml-1.5 h-3.5 w-3.5" />
            </Button>
          </Link>
        </div>
      </div>

      {error && <ErrorBanner message={error} onRetry={() => void loadDashboard(true)} />}

      {loading && !stats && (
        <div className="space-y-6">
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <Skeleton className="h-24 rounded-lg" />
            <Skeleton className="h-24 rounded-lg" />
            <Skeleton className="h-24 rounded-lg" />
            <Skeleton className="h-24 rounded-lg" />
          </div>
          <div className="grid gap-4 lg:grid-cols-12">
            <Skeleton className="h-80 rounded-lg lg:col-span-7" />
            <Skeleton className="h-80 rounded-lg lg:col-span-5" />
          </div>
        </div>
      )}

      {stats && (
        <>
          <DashboardStats stats={stats} />
          <div className="grid gap-4 lg:grid-cols-12">
            <div className="lg:col-span-7">
              <ActivityChart reviews={reviews} />
            </div>
            <div className="lg:col-span-5">
              <RecentReviews reviews={reviews} />
            </div>
          </div>
        </>
      )}
    </div>
  );
}
